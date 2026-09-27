param(
    [string]$InputFile = ".env.local",
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
if ([string]::IsNullOrWhiteSpace($jwtSecret)) {
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

@(
    "# Neon CLI context for the linked shared development branch"
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
    "APP_PUBLIC_BASE_URL=http://localhost:8080"
    "APP_LOG_LEVEL=INFO"
    "JPA_SHOW_SQL=false"
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
    "SELLER_PRODUCT_IMAGE_DIR=uploads/products"
    "VITE_API_BASE_URL=http://localhost:8080/api"
    "FRONTEND_PORT=3000"
) | Set-Content -LiteralPath $OutputFile -Encoding utf8NoBOM

Write-Output "Prepared $OutputFile from $InputFile (credentials were not printed)."
