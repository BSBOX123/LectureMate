#!/bin/bash
# AppleScript 소스로 macOS 앱을 만들고 Desktop 에 별칭을 둔다.
#   scripts/build-apps.sh
# 앱은 저장소 안(scripts/app/)에 두고 Desktop 에는 별칭만 만든다.
# 앱이 자기 위치를 기준으로 프로젝트 경로를 찾기 때문에, 복사본을 Desktop 에 두면 동작하지 않는다.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
PROJECT_DIR="$PWD"

for name in "시작:start" "중지:stop"; do
  label=${name%%:*}; src=${name#*:}
  app="$PROJECT_DIR/scripts/app/LectureMate $label.app"
  rm -rf "$app"
  osacompile -o "$app" "$PROJECT_DIR/scripts/app/$src.applescript"
  echo "빌드: $app"
done

osascript <<APPLESCRIPT
tell application "Finder"
	repeat with label in {"시작", "중지"}
		set appPath to POSIX file ("$PROJECT_DIR/scripts/app/LectureMate " & label & ".app") as alias
		set aliasName to "LectureMate " & label & ".app"
		if exists file aliasName of desktop then delete file aliasName of desktop
		make new alias file at desktop to appPath
	end repeat
end tell
APPLESCRIPT
echo "Desktop 에 별칭 생성 완료"
