param(
    [string]$InputFile = ".env",
    [string]$OutputFile = ".env"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $InputFile)) {
    throw "Missing Neon env file: $InputFile"
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $InputFile) {
    if ($line -match '^\s*([^#=\s]+)=(.*)$') {
        $values[$matches[1]] = $matches[2].Trim().Trim('"')
    }
}

if (Test-Path -LiteralPath ".env") {
    foreach ($line in Get-Content -LiteralPath ".env") {
        if ($line -match '^\s*([^#=\s]+)=(.*)$' -and -not $values.ContainsKey($matches[1])) {
            $values[$matches[1]] = $matches[2].Trim().Trim('"')
        }
    }
}

if (-not $values.ContainsKey("DATABASE_URL") -and -not $values.ContainsKey("DATABASE_URL_UNPOOLED")) {
    throw "DATABASE_URL or DATABASE_URL_UNPOOLED is missing from $InputFile"
}
$jwtSecret = $values["APP_JWT_SECRET"]
if ([string]::IsNullOrWhiteSpace($jwtSecret) -or $jwtSecret -match '^(replace-with|change-this|<)') {
    $jwtSecret = $env:APP_JWT_SECRET
}
if ([string]::IsNullOrWhiteSpace($jwtSecret)) {
    $jwtBytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
    $jwtSecret = [Convert]::ToBase64String($jwtBytes)
}

$databaseUrl = if ($values.ContainsKey("DATABASE_URL_UNPOOLED")) {
    $values["DATABASE_URL_UNPOOLED"]
} else {
    $values["DATABASE_URL"]
}
$connectionUri = [Uri]($databaseUrl -replace '^postgresql://', 'https://')
$userInfo = $connectionUri.UserInfo
$separator = $userInfo.IndexOf(':')
if ($separator -lt 1) {
    throw "Neon database URL does not contain a username and password"
}

$username = [Uri]::UnescapeDataString($userInfo.Substring(0, $separator))
$password = [Uri]::UnescapeDataString($userInfo.Substring($separator + 1))
$passwordOverride = $env:NEON_DB_PASSWORD_OVERRIDE
if (-not [string]::IsNullOrWhiteSpace($passwordOverride)) {
    $password = $passwordOverride
}
$jdbcHost = $connectionUri.Host
$jdbcPort = if (-not $connectionUri.IsDefaultPort) { ":$($connectionUri.Port)" } else { "" }
$jdbcDatabase = $connectionUri.AbsolutePath.TrimStart('/')
$jdbcQuery = ($connectionUri.Query.TrimStart('?').Split('&') | Where-Object { $_ } | ForEach-Object {
    $parts = $_ -split '=', 2
    if ($parts[0] -eq 'channel_binding') {
        "channelBinding=$($parts[1])"
    } else {
        $_
    }
}) -join '&'
$jdbcUrl = "jdbc:postgresql://$jdbcHost$jdbcPort/$jdbcDatabase"
if ($jdbcQuery) {
    $jdbcUrl = "$jdbcUrl`?$jdbcQuery"
}

function First-NonEmptyValue([string[]]$Keys, [string]$Fallback = "") {
    foreach ($key in $Keys) {
        if ($values.ContainsKey($key) -and -not [string]::IsNullOrWhiteSpace($values[$key])) {
            return $values[$key]
        }
    }
    return $Fallback
}

$objectStorageEndpoint = First-NonEmptyValue @("OBJECT_STORAGE_ENDPOINT", "AWS_ENDPOINT_URL_S3")
$objectStorageRegion = First-NonEmptyValue @("OBJECT_STORAGE_REGION", "AWS_REGION") "us-east-2"
$objectStorageBucket = First-NonEmptyValue @("OBJECT_STORAGE_BUCKET") "tcg-product-images"
$objectStorageAccessKey = First-NonEmptyValue @("OBJECT_STORAGE_ACCESS_KEY", "AWS_ACCESS_KEY_ID")
$objectStorageSecretKey = First-NonEmptyValue @("OBJECT_STORAGE_SECRET_KEY", "AWS_SECRET_ACCESS_KEY")
$objectStoragePublicBaseUrl = First-NonEmptyValue @("OBJECT_STORAGE_PUBLIC_BASE_URL")
if ([string]::IsNullOrWhiteSpace($objectStoragePublicBaseUrl) -and -not [string]::IsNullOrWhiteSpace($objectStorageEndpoint)) {
    $objectStoragePublicBaseUrl = "$($objectStorageEndpoint.TrimEnd('/'))/$objectStorageBucket"
}

$missingObjectStorageValues = @(
    if ([string]::IsNullOrWhiteSpace($objectStorageEndpoint)) { "AWS_ENDPOINT_URL_S3" }
    if ([string]::IsNullOrWhiteSpace($objectStorageAccessKey)) { "AWS_ACCESS_KEY_ID" }
    if ([string]::IsNullOrWhiteSpace($objectStorageSecretKey)) { "AWS_SECRET_ACCESS_KEY" }
)
if ($missingObjectStorageValues.Count -gt 0) {
    throw "Missing Neon Object Storage value(s): $($missingObjectStorageValues -join ', '). Enable the bucket and pull the object-storage service."
}

$output = @(
    "# Neon CLI context for the linked shared development branch"
    "DATABASE_URL=$($values['DATABASE_URL'])"
    "DATABASE_URL_UNPOOLED=$($values['DATABASE_URL_UNPOOLED'])"
    "NEON_BRANCH=$($values['NEON_BRANCH'])"
    ""
    "# Active cloud runtime (Spring Boot + Neon)"
    "SPRING_PROFILES_ACTIVE=neon"
    "NEON_JDBC_URL=$jdbcUrl"
    "NEON_DB_USERNAME=$username"
    "NEON_DB_PASSWORD=$password"
    "APP_JWT_SECRET=$jwtSecret"
    "APP_JWT_EXPIRATION_MINUTES=1440"
    "APP_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173,http://localhost:5174,http://127.0.0.1:3000,http://127.0.0.1:5173,http://127.0.0.1:5174"
    "APP_LOG_LEVEL=INFO"
    "JPA_SHOW_SQL=false"
    "OBJECT_STORAGE_ENDPOINT=$objectStorageEndpoint"
    "OBJECT_STORAGE_REGION=$objectStorageRegion"
    "OBJECT_STORAGE_BUCKET=$objectStorageBucket"
    "OBJECT_STORAGE_PUBLIC_BASE_URL=$objectStoragePublicBaseUrl"
    "OBJECT_STORAGE_PATH_STYLE=true"
    "OBJECT_STORAGE_ACCESS_KEY=$objectStorageAccessKey"
    "OBJECT_STORAGE_SECRET_KEY=$objectStorageSecretKey"
    ""
    "# Local Docker PostgreSQL runtime"
    "DB_URL=jdbc:postgresql://localhost:5432/tcg_store"
    "DB_USERNAME=postgres"
    "DB_PASSWORD=postgres"
    "POSTGRES_DB=tcg_store"
    "POSTGRES_USER=postgres"
    "POSTGRES_PASSWORD=postgres"
    "POSTGRES_PORT=5432"
    "SERVER_PORT=8080"
    "VITE_API_BASE_URL=http://localhost:8080/api"
    "FRONTEND_PORT=3000"
    ""
    "# Optional TiDB validation profile"
    "TIDB_JDBC_URL=$($values['TIDB_JDBC_URL'])"
    "TIDB_USER=$($values['TIDB_USER'])"
    "TIDB_PASSWORD=$($values['TIDB_PASSWORD'])"
    "TIDB_POOL_MAX_SIZE=$($values['TIDB_POOL_MAX_SIZE'])"
    "TIDB_POOL_MIN_IDLE=$($values['TIDB_POOL_MIN_IDLE'])"
    "TIDB_SQL_LOG_LEVEL=$($values['TIDB_SQL_LOG_LEVEL'])"
)

$output | Set-Content -LiteralPath $OutputFile -Encoding UTF8

Write-Output "Prepared $OutputFile from $InputFile (credentials were not printed)."
