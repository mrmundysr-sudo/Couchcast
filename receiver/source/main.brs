' Couchcast Receiver - entry point.
' Launch with deep link parameters: /launch/dev?contentId=<url>&mediaType=hls|dash|mp4
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    scene = screen.CreateScene("ReceiverScene")
    screen.show()

    input = CreateObject("roInput")
    input.setMessagePort(port)

    if args <> invalid and args.contentId <> invalid
        scene.mediaRequest = { url: args.contentId, mediaType: args.mediaType }
    end if

    while true
        msg = wait(0, port)
        msgType = type(msg)
        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed() then return
        else if msgType = "roInputEvent"
            info = msg.getInfo()
            if info <> invalid and info.contentId <> invalid
                scene.mediaRequest = { url: info.contentId, mediaType: info.mediaType }
            end if
        end if
    end while
end sub
