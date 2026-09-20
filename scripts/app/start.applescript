-- LectureMate 시작
-- 앱 위치(scripts/app/)를 기준으로 프로젝트 경로를 찾으므로, 저장소를 옮겨도 동작한다.
on run
	set appPath to POSIX path of (path to me)
	set scriptPath to quoted form of (appPath & "../../../scripts/lecturemate.sh")
	try
		display notification "서비스를 시작합니다. 처음에는 2분 정도 걸립니다." with title "LectureMate"
		-- Spring Boot 빌드까지 포함하면 오래 걸려 기본 120초 제한으로는 부족하다
		with timeout of 900 seconds
			do shell script scriptPath & " start"
		end timeout
	on error errorMessage
		display dialog "시작에 실패했습니다:" & return & return & errorMessage with title "LectureMate" buttons {"확인"} default button 1 with icon caution
	end try
end run
