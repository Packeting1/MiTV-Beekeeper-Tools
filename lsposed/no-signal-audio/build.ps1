param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = $env:ANDROID_JAR,
    [string]$BuildTools = $env:BUILD_TOOLS,
    [string]$XposedApiJar = $env:XPOSED_API_JAR,
    [string]$Keystore = $env:KEYSTORE,
    [string]$KeyAlias = $env:KS_ALIAS
)
$ErrorActionPreference = 'Stop'
foreach ($required in @('JavaHome','AndroidJar','BuildTools','XposedApiJar','Keystore','KeyAlias')) {
    if (![string](Get-Variable -Name $required -ValueOnly)) { throw "Set $required (see README.md)" }
}
if (!$env:KS_PASS) { throw 'Set KS_PASS in the local environment' }
foreach ($file in @($AndroidJar,$XposedApiJar,$Keystore)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file missing: $file" }
}
function Tool([string]$directory, [string]$name) {
    foreach ($suffix in @('.exe','')) {
        $path = Join-Path $directory ($name + $suffix)
        if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
    }
    throw "Tool missing: $name"
}
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $exe ($LASTEXITCODE)" }
}
$src = Join-Path $PSScriptRoot 'module'
$build = Join-Path $PSScriptRoot 'build'
$dist = Join-Path $PSScriptRoot 'dist'
$javaBin = Join-Path $JavaHome 'bin'
$java = Tool $javaBin 'java'
New-Item -ItemType Directory -Force $build,"$build/classes","$build/dex",$dist | Out-Null
Run (Tool $BuildTools 'aapt2') @('compile','--dir',"$src/res",'-o',"$build/res.zip")
Run (Tool $BuildTools 'aapt2') @('link','-o',"$build/resources.apk",'-I',$AndroidJar,'--manifest',"$src/AndroidManifest.xml",'-A',"$src/assets","$build/res.zip")
$sources = @(Get-ChildItem "$src/src" -Recurse -Filter '*.java' | ForEach-Object FullName)
Run (Tool $javaBin 'javac') (@('-source','8','-target','8','-Xlint:-options','-encoding','UTF-8','-classpath',"$AndroidJar;$XposedApiJar",'-d',"$build/classes") + $sources)
Run (Tool $javaBin 'jar') @('cf',"$build/classes.jar",'-C',"$build/classes",'.')
Run $java @('-cp',"$BuildTools/lib/d8.jar",'com.android.tools.r8.D8','--release','--min-api','23','--lib',$AndroidJar,'--classpath',$XposedApiJar,'--output',"$build/dex","$build/classes.jar")
Copy-Item -LiteralPath "$build/resources.apk" -Destination "$build/unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$build/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$build/dex/classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
Run (Tool $BuildTools 'zipalign') @('-f','4',"$build/unsigned.apk","$build/aligned.apk")
$apk = Join-Path $dist 'TV-NoSignal-AudioMode-1.0.1.apk'
Run $java @('-jar',"$BuildTools/lib/apksigner.jar",'sign','--ks',$Keystore,'--ks-pass','env:KS_PASS','--ks-key-alias',$KeyAlias,'--out',$apk,"$build/aligned.apk")
Run $java @('-jar',"$BuildTools/lib/apksigner.jar",'verify','--verbose',$apk)
Get-FileHash -LiteralPath $apk
