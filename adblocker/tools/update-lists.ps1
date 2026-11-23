# Downloads the public HaGeZi Pop-Up Ads list (pop-up and redirect ad domains) and
# writes it to rules/popups.json. Run it again any time to refresh the list, then
# reload the extension on the browser's extensions page.
$ErrorActionPreference = 'Stop'

$outPath = Join-Path $PSScriptRoot '..\rules\popups.json'
$listUrl = 'https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt'
$chunkSize = 5000

$text = (Invoke-WebRequest -Uri $listUrl -UseBasicParsing -TimeoutSec 120).Content

# Keep only plain lowercase host names: one bad entry would make the browser reject a rule.
$valid = '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'
$domains = @($text -split "`n" |
    ForEach-Object { $_.Trim().ToLower() } |
    Where-Object { $_ -and $_.Length -le 253 -and $_ -match $valid } |
    Sort-Object -Unique)

if ($domains.Count -lt 1000) {
    throw "Downloaded list looks wrong ($($domains.Count) domains). Nothing was changed."
}

# These rules also block opening the ad page itself (main_frame), which is what stops
# pop-up tabs and redirects.
$types = '"main_frame","sub_frame","script","image","stylesheet","xmlhttprequest","media","websocket","font","ping","object","other"'

$rules = New-Object System.Collections.Generic.List[string]
$id = 1
for ($i = 0; $i -lt $domains.Count; $i += $chunkSize) {
    $end = [Math]::Min($i + $chunkSize, $domains.Count) - 1
    $list = '"' + ($domains[$i..$end] -join '","') + '"'
    $rules.Add("{`"id`":$id,`"priority`":1,`"action`":{`"type`":`"block`"},`"condition`":{`"requestDomains`":[$list],`"resourceTypes`":[$types]}}")
    $id++
}
$json = "[`n" + ($rules -join ",`n") + "`n]`n"

$full = [System.IO.Path]::GetFullPath($outPath)
[System.IO.File]::WriteAllText($full, $json)

Write-Host "Done: $($domains.Count) pop-up ad domains in $($rules.Count) rules, saved to rules/popups.json."
