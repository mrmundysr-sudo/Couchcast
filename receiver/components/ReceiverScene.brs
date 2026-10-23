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
    content.streamFormat = streamFormatFor(request.mediaType, request.url)

    m.status.text = "Loading video..."
    m.player.content = content
    m.player.visible = true
    m.player.control = "play"
    m.player.setFocus(true)
end sub

function streamFormatFor(mediaType as Dynamic, url as String) as String
    t = ""
    if mediaType <> invalid then t = LCase(mediaType)
    lowerUrl = LCase(url)
    if t = "hls" or t = "m3u8" or Instr(1, lowerUrl, ".m3u8") > 0 then return "hls"
    if t = "dash" or t = "mpd" or Instr(1, lowerUrl, ".mpd") > 0 then return "dash"
    return "mp4"
end function

sub onPlayerState()
    state = m.player.state
    if state = "error"
        showIdle("This video could not be played. Try another link from the Couchcast app.")
    else if state = "finished"
        showIdle("Finished. Send another video from the Couchcast app.")
    end if
end sub

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
