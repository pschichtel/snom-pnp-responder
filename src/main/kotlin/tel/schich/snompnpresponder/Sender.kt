package tel.schich.snompnpresponder

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    val channel = DatagramChannel.open(StandardProtocolFamily.INET)
    channel.bind(null)

    val target = InetSocketAddress(InetAddress.getLoopbackAddress(), 5060)
    val payload = Files.readAllBytes(Paths.get("initial-subscribe.txt"))
    channel.send(ByteBuffer.wrap(payload), target)
}