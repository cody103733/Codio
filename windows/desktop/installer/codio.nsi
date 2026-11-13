; Codio installer: one file that takes a PC from nothing to a working Codio.
;  1. Copies Codio (Codio.exe with its player and interface; no streaming server)
;     to %LOCALAPPDATA%\Programs\Codio. Stremio doesn't need to be installed.
;  2. Brings over the sign-in and settings from older Codio versions, which
;     ran inside Stremio's window.
;  3. Adds Desktop and Start menu shortcuts and an uninstaller.
; Per-user install: no admin rights needed.

Unicode true
; 32-bit installer stub (the official NSIS has no amd64 stubs); it installs the 64-bit app.
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"

!ifndef VERSION
  !define VERSION "1.0.0"
!endif
!ifndef SRC
  !define SRC "..\..\dist\Codio-win64"
!endif
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Codio"

Name "Codio"
OutFile "CodioSetup-${VERSION}.exe"
InstallDir "$LOCALAPPDATA\Programs\Codio"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "Codio ${VERSION}"
VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "Codio"
VIAddVersionKey "FileDescription" "Codio installer"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "Codio, GPL-2.0"

!define MUI_ICON "${SRC}\codio.ico"
!define MUI_UNICON "${SRC}\codio.ico"
!define MUI_WELCOMEPAGE_TITLE "Install Codio"
!define MUI_WELCOMEPAGE_TEXT "Codio is a streaming app with smart auto-play: press Play and it finds the best source in your chosen quality, and skips broken ones for you.$\r$\n$\r$\nEverything Codio needs is included. If you used an older Codio, your sign-in, addons and settings come along.$\r$\n$\r$\nClick Install to continue."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Open Codio now"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchCodio
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Var UpdateMode

Function .onInit
  ; "/UPDATE": started by Codio's own updater (with /S). Codio is closing by itself; wait for it,
  ; then install without questions and open the new version.
  StrCpy $UpdateMode "0"
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/UPDATE" $1
  ${IfNot} ${Errors}
    StrCpy $UpdateMode "1"
  ${EndIf}
FunctionEnd

Function WaitForCodio
  StrCpy $2 0
  wait:
  nsExec::ExecToStack 'cmd /c tasklist /NH | findstr /I /L "Codio.exe"'
  Pop $0
  ${If} $0 == 0
    IntOp $2 $2 + 1
    ${If} $2 > 30
      ; Still open after 15 seconds: close it.
      nsExec::ExecToStack 'taskkill /F /IM Codio.exe /T'
      Pop $0
      Sleep 1500
      Return
    ${EndIf}
    Sleep 500
    Goto wait
  ${EndIf}
FunctionEnd

Function CloseRunningApp
  ; Codio must be closed so files can be replaced (older versions ran in stremio-shell-ng.exe).
  ${If} $UpdateMode == "1"
    Call WaitForCodio
  ${EndIf}
  retry:
  nsExec::ExecToStack 'cmd /c tasklist /NH | findstr /I /L "Codio.exe stremio-shell-ng.exe"'
  Pop $0
  ${If} $0 == 0
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "Codio is open.$\r$\n$\r$\nClose it, then click Retry. If it's still running, right-click its icon by the clock and choose Quit." /SD IDCANCEL IDRETRY retry
    Abort
  ${EndIf}
FunctionEnd

Section "Codio" SecMain
  Call CloseRunningApp

  DetailPrint "Installing Codio..."
  ; Replace the interface completely so old builds don't pile up.
  RMDir /r "$INSTDIR\web"
  ; Files of Codio 1.2 and older (which started through Stremio).
  Delete "$INSTDIR\launcher.js"
  Delete "$INSTDIR\Codio.vbs"
  ; Codio 1.5.x and older shipped a streaming server and its runtime: remove them.
  Delete "$INSTDIR\server.js"
  Delete "$INSTDIR\stremio-runtime.exe"
  SetOutPath "$INSTDIR"
  File "${SRC}\Codio.exe"
  File "${SRC}\codio-runtime.exe"
  ; (no streaming server: debrid / HTTP sources play straight in mpv)
  File "${SRC}\codio-web.js"
  File "${SRC}\codio-downloads.js"
  File "${SRC}\codio-update.js"
  ; ffmpeg was only used by the streaming server (gone since 1.6.1).
  Delete "$INSTDIR\ffmpeg.exe"
  Delete "$INSTDIR\ffprobe.exe"
  File "${SRC}\*.dll"
  File "${SRC}\codio.ico"
  File "/oname=Codio-icon-2.ico" "${SRC}\codio.ico"
  File "${SRC}\README.txt"
  SetOutPath "$INSTDIR\web"
  File /r "${SRC}\web\*.*"
  SetOutPath "$INSTDIR"

  ; Keep the sign-in, addons and settings of older Codio versions (they lived in Stremio's window data).
  ${IfNot} ${FileExists} "$INSTDIR\Codio.exe.WebView2\*.*"
    ${If} ${FileExists} "$LOCALAPPDATA\Programs\Stremio\stremio-shell-ng.exe.WebView2\*.*"
      DetailPrint "Bringing over your sign-in and settings..."
      CreateDirectory "$INSTDIR\Codio.exe.WebView2"
      CopyFiles /SILENT "$LOCALAPPDATA\Programs\Stremio\stremio-shell-ng.exe.WebView2\*.*" "$INSTDIR\Codio.exe.WebView2"
    ${ElseIf} ${FileExists} "$PROGRAMFILES64\Stremio\stremio-shell-ng.exe.WebView2\*.*"
      CreateDirectory "$INSTDIR\Codio.exe.WebView2"
      CopyFiles /SILENT "$PROGRAMFILES64\Stremio\stremio-shell-ng.exe.WebView2\*.*" "$INSTDIR\Codio.exe.WebView2"
    ${EndIf}
  ${EndIf}

  ; Shortcuts take their icon from Codio-icon-2.ico, a file name Windows has no cached (blank) copy of.
  Delete "$DESKTOP\Codio.lnk"
  Delete "$SMPROGRAMS\Codio.lnk"
  CreateShortcut "$DESKTOP\Codio.lnk" "$INSTDIR\Codio.exe" "" "$INSTDIR\Codio-icon-2.ico" 0 SW_SHOWNORMAL "" "Codio"
  CreateShortcut "$SMPROGRAMS\Codio.lnk" "$INSTDIR\Codio.exe" "" "$INSTDIR\Codio-icon-2.ico" 0 SW_SHOWNORMAL "" "Codio"

  ; Codio 1.3.0 shipped a blank icon and Windows keeps a cached copy of it: refresh the icon cache.
  nsExec::ExecToStack '"$SYSDIR\ie4uinit.exe" -show'
  Pop $0
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'


  ; Uninstaller and the Apps & features entry.
  WriteUninstaller "$INSTDIR\Uninstall Codio.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "Codio"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "Codio"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\Codio-icon-2.ico"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall Codio.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" $0

  ; An update started from Codio: open the new version (the finish page isn't shown in silent mode).
  ${If} $UpdateMode == "1"
    Exec '"$INSTDIR\Codio.exe"'
  ${EndIf}
SectionEnd

Function LaunchCodio
  Exec '"$INSTDIR\Codio.exe"'
FunctionEnd

Section "Uninstall"
  nsExec::ExecToStack 'taskkill /IM Codio.exe /T'
  Pop $0
  Sleep 1500
  Delete "$DESKTOP\Codio.lnk"
  Delete "$SMPROGRAMS\Codio.lnk"
  RMDir /r "$INSTDIR"
  RMDir /r "$LOCALAPPDATA\Codio"
  DeleteRegKey HKCU "${UNINST_KEY}"
SectionEnd
