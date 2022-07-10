package tel.schich.snompnpresponder

import gov.nist.core.Host
import gov.nist.core.HostPort
import gov.nist.javax.sip.header.CSeq
import gov.nist.javax.sip.header.Contact
import gov.nist.javax.sip.header.ContentType
import gov.nist.javax.sip.header.From
import gov.nist.javax.sip.header.MaxForwards
import gov.nist.javax.sip.header.SubscriptionState
import gov.nist.javax.sip.header.To
import gov.nist.javax.sip.header.Via
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import gov.nist.javax.sip.parser.AddressParser
import gov.nist.javax.sip.parser.MessageParser
import gov.nist.javax.sip.parser.StringMsgParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey.OP_READ
import java.nio.channels.SelectionKey.OP_WRITE
import java.nio.channels.Selector
import java.util.Random
import kotlin.concurrent.thread
import kotlin.system.exitProcess

val SubscriptionStateHeader = SubscriptionState().also {
    it.state = "terminated"
    it.reasonCode = "timeout"
}
val MaxForwardsHeader = MaxForwards().also {
    it.maxForwards = 20
}
const val PORT = 5060


suspend fun main(args: Array<String>) {
    val interfaceName = args.getOrNull(0) ?: run {
        println("No device given!")
        exitProcess(1)
    }

    val settingsServerUrl = args.getOrNull(1) ?: run {
        println("No settings server url given!")
        exitProcess(1)
    }

    val iface = NetworkInterface.getByName(interfaceName)
    val address = iface.inetAddresses
        .asSequence()
        .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        .filter { it.address.size == 4 }
        .first()

    println("Detected $address as my own address.")

    val input = Channel<Pair<SocketAddress, ByteArray>>()
    val output = Channel<Pair<SocketAddress, ByteArray>>()
    coroutineScope {
        launch {
            processFrames(input, output, address, settingsServerUrl)
        }
        listenAsync(iface, input, output).await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun CoroutineScope.listenAsync(
    iface: NetworkInterface,
    input: SendChannel<Pair<SocketAddress, ByteArray>>,
    output: ReceiveChannel<Pair<SocketAddress, ByteArray>>,
): Deferred<Unit> {
    val selector = Selector.open()

    val promise = CompletableDeferred<Unit>()
    val outputIntermediate = Channel<Pair<SocketAddress, ByteArray>>(1)
    launch {
        for (pair in output) {
            outputIntermediate.send(pair)
            selector.wakeup()
        }
        outputIntermediate.close()
    }
    thread(isDaemon = true, name = "network listener") {
        val multicastGroup = InetAddress.getByName("sip.mcast.net")
        println("Joining multicast group: ${multicastGroup.hostAddress} on interface ${iface.name}")
        val channel = selector.provider().openDatagramChannel(StandardProtocolFamily.INET)
            .setOption(StandardSocketOptions.SO_REUSEADDR, true)
            .setOption(StandardSocketOptions.IP_MULTICAST_IF, iface)
            .bind(InetSocketAddress(PORT))

        channel.join(multicastGroup, iface)
        channel.configureBlocking(false)

        val registration = channel.register(selector, OP_READ or OP_WRITE)
        val buffer = ByteBuffer.allocateDirect(8196)
        var pendingWrite: Pair<SocketAddress, ByteArray>? = null

        while (!input.isClosedForSend && !outputIntermediate.isClosedForReceive) {
            if (pendingWrite == null) {
                pendingWrite = outputIntermediate.tryReceive().getOrNull()
            }
            if (pendingWrite != null) {
                registration.interestOps(OP_READ or OP_WRITE)
            } else {
                registration.interestOps(OP_READ)
            }
            val ready = selector.select(2000L)
            if (ready > 0) {
                val selectedKeyIterator = selector.selectedKeys().iterator()
                while (selectedKeyIterator.hasNext()) {
                    val key = selectedKeyIterator.next()
                    if (key.channel() == channel) {
                        selectedKeyIterator.remove()
                        if (key.isReadable) {
                            buffer.clear()
                            val address = channel.receive(buffer)
                            buffer.flip()
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes, 0, buffer.remaining())
                            runBlocking {
                                input.send(Pair(address, bytes))
                            }
                        }
                        if (key.isWritable && pendingWrite != null) {
                            val (target, payload) = pendingWrite
                            channel.send(ByteBuffer.wrap(payload), target)
                            pendingWrite = null
                        }
                    }
                }
            }
        }
        promise.complete(Unit)
    }
    return promise
}

private suspend fun processFrames(
    input: ReceiveChannel<Pair<SocketAddress, ByteArray>>,
    output: SendChannel<Pair<SocketAddress, ByteArray>>,
    address: InetAddress,
    settingsServerUrl: String,
) {
    val random = Random()
    val parser = StringMsgParser()
    for ((source, payload) in input) {
        processFrame(parser, source, payload, output, random, address, settingsServerUrl)
    }
}

private suspend fun processFrame(
    parser: MessageParser,
    source: SocketAddress,
    payload: ByteArray,
    output: SendChannel<Pair<SocketAddress, ByteArray>>,
    random: Random,
    address: InetAddress,
    settingsServerUrl: String,
) {
    // based on:
    // * https://service.snom.com/display/wiki/Auto+Provisioning
    // * https://service.snom.com/pages/viewpage.action?pageId=17370665
    when (val message = parser.parseSIPMessage(payload, true, false, null)) {
        is SIPRequest -> {
            val contactHeader = Contact().also {
                it.address = AddressParser("<sip:${address.hostAddress};transport=TCP;handler=dum>")
                    .address(true)
            }

            val headers = message.headers.asSequence().toList()
            val maxHeaderLength = headers.maxOfOrNull { it.headerName.length } ?: 0

            println("Received a ${message.method} from $source")
            println("Headers:")
            for (header in headers) {
                println("  ${header.headerName.padStart(maxHeaderLength, ' ')}: ${header.headerValue}")
            }

            val response = message.createResponse(200, "OK")
            response.to.tag = random.nextInt().toUInt().toString()
            response.setHeader(contactHeader)
            output.send(Pair(source, response.encodeAsBytes("udp")))

            val notify = SIPRequest()
            notify.cSeq = CSeq(3, "NOTIFY")
            notify.method = notify.cSeq.method
            notify.sipVersion = message.sipVersion
            notify.requestURI = message.contactHeader.address.uri
            notify.cSeq.seqNumber = 3
            notify.callId = message.callId
            notify.to = To().also {
                it.address = message.from.address
                it.tag = message.from.tag
            }
            notify.from = From().also {
                it.address = message.to.address
                it.tag = response.to.tag
            }
            notify.setHeader(SubscriptionStateHeader)
            notify.setHeader(message.getHeader("event"))
            notify.maxForwards = MaxForwardsHeader
            notify.setHeader(contactHeader)
            val viaHeader = Via().also {
                it.protocolVersion = "2.0"
                it.transport = "UDP"
                it.sentBy = HostPort().also { hostPort ->
                    hostPort.host = Host().also { host ->
                        host.address = address.hostAddress
                    }
                    hostPort.port = PORT
                }
            }
            notify.setHeaders(listOf(viaHeader))
            notify.setContent(settingsServerUrl.toByteArray(), ContentType("application", "url"))

            output.send(Pair(source, notify.encodeAsBytes("udp")))
        }
        is SIPResponse -> {
            println("Received a response: $message")
        }
        else -> {
            println("Received an unknown type of SIP message: $message")
        }
    }
}