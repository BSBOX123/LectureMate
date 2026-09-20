-- LectureMate 중지
on run
	set appPath to POSIX path of (path to me)
	set scriptPath to quoted form of (appPath & "../../../scripts/lecturemate.sh")
	try
		with timeout of 300 seconds
			do shell script scriptPath & " stop"
		end timeout
		display notification "서비스를 중지했습니다." with title "LectureMate"
	on error errorMessage
		display dialog "중지에 실패했습니다:" & return & return & errorMessage with title "LectureMate" buttons {"확인"} default button 1 with icon caution
	end try
end run
