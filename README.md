# vr-360video-remote
android 360-video app for park attractions

based on android [360 sample](https://developers.google.com/vr/android/samples/video360)

# How to use

It listens 11111 port by default.

Command format (arguments separated by spaces):
`path-to-file mstime roll pitch yaw` or `mstime roll pitch yaw`

## Linux command

- Launch: `echo "file:///storage/emulated/0/Movies/testRoom_Mono.mp4" | socat - UDP-DATAGRAM:192.168.0.255:11111,broadcast`
- Set time: `echo "file:///storage/emulated/0/Movies/testRoom_Mono.mp4 123 0 0 0" | socat - UDP-DATAGRAM:192.168.0.255:11111,broadcast`
