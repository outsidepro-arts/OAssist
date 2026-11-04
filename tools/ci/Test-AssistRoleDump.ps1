$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '../run-assist-capture-tests.ps1'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Cannot parse assist capture script' }
$function = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'ConvertFrom-RoleDump' }, $false))
if ($function.Count -ne 1) { throw 'Cannot find role dump parser' }
Invoke-Expression $function[0].Extent.Text

function Assert-Throws([scriptblock]$Action, [string]$Message) {
  try { & $Action | Out-Null } catch { return }
  throw "Expected failure: $Message"
}
function Make-Dump([string]$Users) { return "ROLE STATE (dumpsys role):`n{`n user_states={`n$Users`n }`n}" }
function Make-User([string]$Id, [string]$Roles) { return "user_id=$Id`nversion=-1`npackages_hash=ABC123`nroles=[`n$Roles`n]" }
$assistant = 'android.app.role.ASSISTANT'
$empty = "{`n name=$assistant`n}"
$other = '{ name=android.app.role.HOME holders=com.example.home }'
$selected = "{ name=$assistant holders=com.example.z holders=com.example.a }"
$dump = Make-Dump ((Make-User '0' "$other`n$selected") + "`n" + (Make-User '10' $empty))
if ((@(ConvertFrom-RoleDump $dump '0' $assistant) -join ',') -cne 'com.example.a,com.example.z') { throw 'Wrong user or holders' }
if (@(ConvertFrom-RoleDump $dump '10' $assistant).Count -ne 0) { throw 'Empty role must have no holders' }
Assert-Throws { ConvertFrom-RoleDump $dump '11' $assistant } 'Missing user'
Assert-Throws { ConvertFrom-RoleDump $dump '10' 'android.app.role.HOME' } 'Missing role'
Assert-Throws { ConvertFrom-RoleDump (Make-Dump ((Make-User '0' $empty) + "`n" + (Make-User '0' $empty))) '0' $assistant } 'Duplicate user'
Assert-Throws { ConvertFrom-RoleDump (Make-Dump (Make-User '0' "$empty`n$empty")) '0' $assistant } 'Duplicate role'
Assert-Throws { ConvertFrom-RoleDump (Make-Dump (Make-User '0' "{ name=$assistant holders=com.example.a holders=com.example.a }")) '0' $assistant } 'Duplicate holder'
Assert-Throws { ConvertFrom-RoleDump ($dump -replace 'holders=com.example.z', 'holders=bad-package') '0' $assistant } 'Malformed holder'
Assert-Throws { ConvertFrom-RoleDump ($dump -replace 'name=android.app.role.ASSISTANT', 'name=android.app.role.ASSISTANT unknown=value') '0' $assistant } 'Unknown field'
Assert-Throws { ConvertFrom-RoleDump $dump '0' 'android.app.role.assistant' } 'Role names are case sensitive'
Assert-Throws { ConvertFrom-RoleDump $dump.Substring(0, $dump.Length - 1) '0' $assistant } 'Truncated dump'
Write-Output 'Assist role dump parser tests passed.'
