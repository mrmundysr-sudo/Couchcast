sub init()
    m.title = m.top.findNode("title")
    m.status = m.top.findNode("status")
    m.player = m.top.findNode("player")
    m.player.observeField("state", "onPlayerState")
    m.top.setFocus(true)
end sub

sub onMediaRequest()
    request = m.top.mediaRequest
    if request = invalid or request.url = invalid then return

    content = createObject("roSGNode", "ContentNode")
    content.url = request.url
    content.streamFormat = streamFormatFor(request.format, request.url)

    m.status.text = "Loading video..."
    m.player.content = content
    m.player.visible = true
    m.player.control = "play"
    m.player.setFocus(true)
end sub

function streamFormatFor(hint as Dynamic, url as String) as String
    t = ""
    if hint <> invalid then t = LCase(hint)
    lowerUrl = LCase(url)
    if t = "hls" or Instr(1, lowerUrl, ".m3u8") > 0 then return "hls"
    if t = "dash" or Instr(1, lowerUrl, ".mpd") > 0 then return "dash"
    if t = "mkv" or Instr(1, lowerUrl, ".mkv") > 0 then return "mkv"
    return "mp4"
end function

sub onPlayerState()
    state = m.player.state
    if state = "error"
        showError()
    else if state = "finished"
        showIdle("Finished. Send another video from the Couchcast app.")
    end if
end sub

' Keep the reason on screen so a failed video can be diagnosed from the TV.
sub showError()
    p = m.player
    text = "This video could not be played."
    msg = safeStr(p.errorMsg)
    if msg <> "" then text = text + chr(10) + msg
    code = safeStr(p.errorCode)
    if code <> "" then text = text + chr(10) + "Error code " + code
    detail = safeStr(p.errorStr)
    if detail <> "" then text = text + chr(10) + detail
    vf = safeStr(p.videoFormat)
    af = safeStr(p.audioFormat)
    if vf <> "" or af <> "" then text = text + chr(10) + "video: " + vf + "   audio: " + af
    showIdle(text)
end sub

function safeStr(v as Dynamic) as String
    if v = invalid then return ""
    t = type(v)
    if t = "roString" or t = "String" then return v
    if t = "roInt" or t = "Integer" or t = "roInteger" then return v.toStr()
    return ""
end function

sub showIdle(text as String)
    m.player.control = "stop"
    m.player.visible = false
    m.status.text = text
    m.top.setFocus(true)
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if press and key = "back" and m.player.visible
        showIdle("Stopped. Send another video from the Couchcast app.")
        return true
    end if
    return false
end function
