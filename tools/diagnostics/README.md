# Diagnostic archive inspector

Run from the repository root with the project Gradle wrapper:

```powershell
.\gradlew.bat :diagnostic-lint:inspectDiagnostics --args='C:\path\report.zip'
.\gradlew.bat :diagnostic-lint:inspectDiagnostics --args='C:\path\report.zip operation-id'
```

The inspector validates the archive structure, CRC and format version, prints a timeline,
and reports tool calls and results without a matching counterpart. Missing pairs can
also mean the report is incomplete. Repeated provider call IDs are counted in order.
The optional operation ID filters to that operation and its immediate children. It does
not execute commands or send network requests. Diagnostic reports contain private data;
inspect them only with the owner's permission.
