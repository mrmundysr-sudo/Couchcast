sub init()
    m.title = m.top.findNode("title")
    m.status = m.top.findNode("status")
    m.player = m.top.findNode("player")
    m.photo = m.top.findNode("photo")
    m.photo.observeField("loadStatus", "onPhotoStatus")
    m.player.observeField("state", "onPlayerState")
    m.top.setFocus(true)
end sub

sub onMediaRequest()
    request = m.top.mediaRequest
    if request = invalid or request.url = invalid then return

    if isPhotoRequest(request)
        showPhoto(request.url)
        return
    end if
    m.photo.visible = false

    content = createObject("roSGNode", "ContentNode")
    content.url = request.url
    content.streamFormat = streamFormatFor(request.format, request.url)
    headers = buildHeaders(request.referer, request.useragent)
    if headers.count() > 0 then content.HttpHeaders = headers

    m.status.text = "Loading video..."
    m.player.content = content
    m.player.visible = true
    m.player.control = "play"
    m.player.setFocus(true)
end sub

function isPhotoRequest(request as Object) as Boolean
    if request.mediaType <> invalid and LCase(request.mediaType) = "photo" then return true
    if request.format <> invalid and LCase(request.format) = "photo" then return true
    return false
end function

' A still picture from the phone. The Roku downloads it from the phone's relay and shows it full screen.
sub showPhoto(url as String)
    m.player.control = "stop"
    m.player.visible = false
    m.status.text = "Loading photo..."
    m.photo.uri = ""
    m.photo.uri = url
    m.photo.visible = true
    m.top.setFocus(true)
end sub

sub onPhotoStatus()
    if not m.photo.visible then return
    status = m.photo.loadStatus
    if status = "ready"
        m.title.visible = false
        m.status.visible = false
    else if status = "failed"
        showIdle("This photo could not be shown." + chr(10) + "Send it again from the Couchcast app.")
    end if
end sub

' Some sites only serve a stream to requests that look like they came from the page that embeds it.
' The app sends that page's address (and the browser's identity) only for videos it found by scanning a page.
function buildHeaders(referer as Dynamic, userAgent as Dynamic) as Object
    headers = []
    ref = cleanHeader(referer)
    if ref <> ""
        headers.push("Referer: " + ref)
        origin = originOf(ref)
        if origin <> "" then headers.push("Origin: " + origin)
    end if
    ua = cleanHeader(userAgent)
    if ua <> "" then headers.push("User-Agent: " + ua)
    return headers
end function

function cleanHeader(v as Dynamic) as String
    if v = invalid then return ""
    t = type(v)
    if t <> "roString" and t <> "String" then return ""
    out = v
    out = out.replace(chr(13), "")
    out = out.replace(chr(10), "")
    return out.trim()
end function

function originOf(url as String) as String
    schemeEnd = Instr(1, url, "://")
    if schemeEnd = 0 then return ""
    slash = Instr(schemeEnd + 3, url, "/")
    if slash = 0 then return url
    return Left(url, slash - 1)
end function

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
    m.photo.visible = false
    m.photo.uri = ""
    m.title.visible = true
    m.status.visible = true
    m.status.text = text
    m.top.setFocus(true)
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if press and key = "back" and (m.player.visible or m.photo.visible)
        showIdle("Stopped. Send another video from the Couchcast app.")
        return true
    end if
    return false
end function
