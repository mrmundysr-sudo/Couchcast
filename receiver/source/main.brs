' Couchcast Receiver - entry point.
' Launch with deep link parameters: /launch/dev?contentId=<url>&mediaType=shortFormVideo&format=hls|dash|mp4|mkv[&referer=<page>&useragent=<ua>]
' While running, the same parameters arrive as an roInput event (ECP POST /input).
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    scene = screen.CreateScene("ReceiverScene")
    screen.show()

    input = CreateObject("roInput")
    input.setMessagePort(port)

    if args <> invalid and args.contentId <> invalid
        scene.mediaRequest = { url: args.contentId, format: args.format, referer: args.referer, useragent: args.useragent }
    end if

    while true
        msg = wait(0, port)
        msgType = type(msg)
        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed() then return
        else if msgType = "roInputEvent"
            info = msg.getInfo()
            if info <> invalid and info.contentId <> invalid
                scene.mediaRequest = { url: info.contentId, format: info.format, referer: info.referer, useragent: info.useragent }
            end if
        end if
    end while
end sub
