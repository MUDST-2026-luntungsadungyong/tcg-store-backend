[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SellerEmail = "seller@mythix.com",
    [string]$ManagedUrlPrefix,
    [int]$MaxItems = 0
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:TCG_MIGRATION_PASSWORD)) {
    throw "Set TCG_MIGRATION_PASSWORD in the current process before running this migration."
}

$apiBaseUrl = $BaseUrl.TrimEnd("/")
$loginBody = @{
    email = $SellerEmail
    password = $env:TCG_MIGRATION_PASSWORD
} | ConvertTo-Json

$auth = Invoke-RestMethod `
    -Uri "$apiBaseUrl/api/auth/login" `
    -Method Post `
    -ContentType "application/json" `
    -Body $loginBody

if ([string]::IsNullOrWhiteSpace($auth.token)) {
    throw "Login succeeded without returning an access token."
}

$headers = @{ Authorization = "Bearer $($auth.token)" }
$productResponse = Invoke-RestMethod `
    -Uri "$apiBaseUrl/api/seller/products?page=0&size=100" `
    -Method Get `
    -Headers $headers
$products = [System.Collections.Generic.List[object]]::new()
foreach ($product in $productResponse) {
    $products.Add($product)
}

$candidates = @($products | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_.image) -and
    ([string]::IsNullOrWhiteSpace($ManagedUrlPrefix) -or
        -not ([string]$_.image).StartsWith($ManagedUrlPrefix.TrimEnd("/"), [System.StringComparison]::OrdinalIgnoreCase))
})

if ($MaxItems -gt 0) {
    $candidates = @($candidates | Select-Object -First $MaxItems)
}

if ($candidates.Count -eq 0) {
    Write-Output "No product images require migration."
    exit 0
}

$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("tcg-s3-migration-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null

$migrated = 0
$failed = 0

try {
    foreach ($product in $candidates) {
        $sourceUri = $null
        if (-not [uri]::TryCreate([string]$product.image, [System.UriKind]::Absolute, [ref]$sourceUri) -or
            $sourceUri.Scheme -notin @("http", "https")) {
            Write-Warning "Product $($product.id) has an unsupported image URL and was skipped."
            $failed++
            continue
        }

        $extension = [System.IO.Path]::GetExtension($sourceUri.AbsolutePath).ToLowerInvariant()
        if ($extension -notin @(".png", ".jpg", ".jpeg", ".webp")) {
            $extension = ".img"
        }

        $temporaryFile = Join-Path $temporaryDirectory ("product-$($product.id)$extension")

        try {
            $downloadResponse = Invoke-WebRequest `
                -Uri $sourceUri.AbsoluteUri `
                -OutFile $temporaryFile `
                -PassThru `
                -Headers @{ "User-Agent" = "tcg-store-product-image-migration/1.0" }

            $contentType = ([string]$downloadResponse.Headers["Content-Type"] -split ";", 2)[0].Trim().ToLowerInvariant()
            if ($contentType -notin @("image/png", "image/jpeg", "image/webp")) {
                throw "Source returned unsupported content type '$contentType'."
            }

            $normalizedCondition = switch (([string]$product.condition).Trim().ToUpperInvariant()) {
                { $_ -in @("MINT", "NEAR MINT", "NM") } { "NM"; break }
                { $_ -in @("LIGHTLY PLAYED", "LP") } { "LP"; break }
                { $_ -in @("MODERATELY PLAYED", "MP") } { "MP"; break }
                { $_ -in @("HEAVILY PLAYED", "HP") } { "HP"; break }
                { $_ -in @("DAMAGED", "DMG") } { "DMG"; break }
                default { [string]$product.condition }
            }

            $form = @{
                name = [string]$product.name
                game = [string]$product.game
                imageUrl = [string]$product.image
                price = ([decimal]$product.price).ToString([System.Globalization.CultureInfo]::InvariantCulture)
                cost = ([decimal]$product.cost).ToString([System.Globalization.CultureInfo]::InvariantCulture)
                stock = [string]$product.stock
                status = [string]$product.status
                condition = $normalizedCondition
            }

            foreach ($optionalField in @("language", "set", "rarity", "cardId")) {
                if (-not [string]::IsNullOrWhiteSpace($product.$optionalField)) {
                    $form[$optionalField] = [string]$product.$optionalField
                }
            }

            $httpClient = [System.Net.Http.HttpClient]::new()
            $request = [System.Net.Http.HttpRequestMessage]::new(
                [System.Net.Http.HttpMethod]::Put,
                "$apiBaseUrl/api/seller/products/$($product.id)"
            )
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $auth.token)
            $multipart = [System.Net.Http.MultipartFormDataContent]::new()
            $fileStream = $null

            try {
                foreach ($fieldName in $form.Keys) {
                    $multipart.Add(
                        [System.Net.Http.StringContent]::new([string]$form[$fieldName], [System.Text.Encoding]::UTF8),
                        [string]$fieldName
                    )
                }

                $fileStream = [System.IO.File]::OpenRead($temporaryFile)
                $fileContent = [System.Net.Http.StreamContent]::new($fileStream)
                $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new($contentType)
                $multipart.Add($fileContent, "image", [System.IO.Path]::GetFileName($temporaryFile))
                $request.Content = $multipart

                $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
                $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if (-not $response.IsSuccessStatusCode) {
                    throw "Upload returned HTTP $([int]$response.StatusCode): $responseBody"
                }
                $updated = $responseBody | ConvertFrom-Json
            }
            finally {
                $request.Dispose()
                $multipart.Dispose()
                $httpClient.Dispose()
            }

            if (-not [string]::IsNullOrWhiteSpace($ManagedUrlPrefix) -and
                -not ([string]$updated.image).StartsWith($ManagedUrlPrefix.TrimEnd("/"), [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Product $($product.id) was updated but returned an unmanaged image URL."
            }

            $migrated++
            Write-Output "Migrated product $($product.id) ($migrated/$($candidates.Count))."
        }
        catch {
            $failed++
            $errorMessage = $_.Exception.Message
            if (-not [string]::IsNullOrWhiteSpace($_.ErrorDetails.Message)) {
                $errorMessage = "$errorMessage $($_.ErrorDetails.Message)"
            }
            Write-Warning "Product $($product.id) failed: $errorMessage"
        }
        finally {
            Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
        }
    }
}
finally {
    Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "Migration complete: migrated=$migrated failed=$failed total=$($candidates.Count)."
if ($failed -gt 0) {
    exit 1
}
