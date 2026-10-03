$ErrorActionPreference = 'Stop'

function Read-PrivateText([string]$prompt) {
    $secure = Read-Host -Prompt $prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

$email = Read-PrivateText 'Account email (hidden)'
$password = Read-PrivateText 'Password (hidden)'
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
try {
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('Mozilla/5.0 zComic/0.1')
    $page = $client.GetAsync('https://kxo.moe/login.php').GetAwaiter().GetResult()
    if (-not $page.IsSuccessStatusCode) { throw 'Login page request failed' }

    $body = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $body.Add([System.Net.Http.StringContent]::new($email), 'email')
        $body.Add([System.Net.Http.StringContent]::new($password), 'passwd')
        $request = [System.Net.Http.HttpRequestMessage]::new('POST', 'https://kxo.moe/login_act.php')
        try {
            $request.Content = $body
            $request.Headers.Referrer = [Uri]'https://kxo.moe/login.php'
            $request.Headers.Add('X-KM-FROM', 'KMOE/3.0.0 POST /login.php')
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) { throw 'Login request failed' }
            $result = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if ($result.msgid -eq 'm100') {
                Write-Output 'LOGIN_TEST_PASS'
            } else {
                Write-Output "LOGIN_TEST_FAIL: $($result.msgid)"
                exit 1
            }
        } finally {
            $request.Dispose()
        }
    } finally {
        $body.Dispose()
    }
} catch {
    Write-Output 'LOGIN_TEST_ERROR'
    exit 1
} finally {
    $client.Dispose()
    $handler.Dispose()
    $email = $null
    $password = $null
}
