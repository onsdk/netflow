package io.netflow.flows.cflow

import java.net.{ InetAddress, InetSocketAddress }
import java.util.UUID

import com.datastax.driver.core.Row
import com.datastax.driver.core.utils.UUIDs
import com.websudos.phantom.Implicits._
import io.netflow.lib._
import io.netty.buffer._
import net.liftweb.json.JsonDSL._
import org.joda.time.DateTime

import scala.util.{ Failure, Try }

/**
 * NetFlow Version 6
 *
 * *-------*---------------*------------------------------------------------------*
 * | Bytes | Contents      | Description                                          |
 * *-------*---------------*------------------------------------------------------*
 * | 0-1   | version       | The version of NetFlow records exported 005          |
 * *-------*---------------*------------------------------------------------------*
 * | 2-3   | count         | Number of flows exported in this packet (1-30)       |
 * *-------*---------------*------------------------------------------------------*
 * | 4-7   | SysUptime     | Current time in milli since the export device booted |
 * *-------*---------------*------------------------------------------------------*
 * | 8-11  | unix_secs     | Current count of seconds since 0000 UTC 1970         |
 * *-------*---------------*------------------------------------------------------*
 * | 12-15 | unix_nsecs    | Residual nanoseconds since 0000 UTC 1970             |
 * *-------*---------------*------------------------------------------------------*
 * | 16-19 | flow_sequence | Sequence counter of total flows seen                 |
 * *-------*---------------*------------------------------------------------------*
 * | 20    | engine_type   | Type of flow-switching engine                        |
 * *-------*---------------*------------------------------------------------------*
 * | 21    | engine_id     | Slot number of the flow-switching engine             |
 * *-------*---------------*------------------------------------------------------*
 * | 22-23 | sampling_int  | First two bits hold the sampling mode                |
 * |       |               | remaining 14 bits hold value of sampling interval    |
 * *-------*---------------*------------------------------------------------------*
 */

object NetFlowV6Packet {
  private val headerSize = 24
  private val flowSize = 52

  /**
   * Parse a Version 6 FlowPacket
   *
   * @param sender The sender's InetSocketAddress
   * @param buf Netty ByteBuf containing the UDP Packet
   */
  def apply(sender: InetSocketAddress, buf: ByteBuf): Try[NetFlowV6Packet] = Try[NetFlowV6Packet] {
    val version = buf.getUnsignedInteger(0, 2).toInt
    if (version != 6) return Failure(new InvalidFlowVersionException(version))

    val count = buf.getUnsignedInteger(2, 2).toInt
    if (count <= 0 || buf.readableBytes < headerSize + count * flowSize)
      return Failure(new CorruptFlowPacketException)

    val uptime = buf.getUnsignedInteger(4, 4)
    val timestamp = new DateTime(buf.getUnsignedInteger(8, 4) * 1000)
    val id = UUIDs.startOf(timestamp.getMillis)
    val flowSequence = buf.getUnsignedInteger(16, 4)
    val engineType = buf.getUnsignedInteger(20, 1).toInt
    val engineId = buf.getUnsignedInteger(21, 1).toInt
    // the first 2 bits are the sampling mode, the remaining 14 the interval
    val sampling = buf.getUnsignedInteger(22, 2).toInt
    val samplingInterval = sampling & 0x3FFF
    val samplingMode = sampling >> 14

    val flows: List[NetFlowV6Record] = (0 to count - 1).toList.flatMap { i =>
      NetFlowV6(sender, buf.slice(headerSize + (i * flowSize), flowSize), id, uptime, timestamp, samplingInterval)
    }
    NetFlowV6Packet(id, sender, buf.readableBytes, uptime, timestamp, flows, flowSequence, engineType, engineId,
      samplingInterval, samplingMode)
  }
}

case class NetFlowV6Packet(id: UUID, sender: InetSocketAddress, length: Int, uptime: Long, timestamp: DateTime,
                           flows: List[NetFlowV6Record],
                           flowSequence: Long, engineType: Int, engineId: Int,
                           samplingInterval: Int, samplingMode: Int) extends FlowPacket {
  def version = "NetFlowV6 Packet"
  def count = flows.length

  def persist() = flows.foldLeft((0, new BatchStatement())) {
    case ((count, b), row) =>
      val statement = NetFlowV6.insert
        .value(_.id, UUIDs.startOf(timestamp.getMillis + count))
        .value(_.packet, id)
        .value(_.sender, row.sender.getAddress)
        .value(_.timestamp, row.timestamp)
        .value(_.uptime, row.uptime)
        .value(_.senderPort, row.senderPort)
        .value(_.length, row.length)
        .value(_.srcPort, row.srcPort)
        .value(_.dstPort, row.dstPort)
        .value(_.srcAS, row.srcAS)
        .value(_.dstAS, row.dstAS)
        .value(_.pkts, row.pkts)
        .value(_.bytes, row.bytes)
        .value(_.proto, row.proto)
        .value(_.tos, row.tos)
        .value(_.tcpflags, row.tcpflags)
        .value(_.start, row.start)
        .value(_.stop, row.stop)
        .value(_.srcAddress, row.srcAddress)
        .value(_.dstAddress, row.dstAddress)
        .value(_.nextHop, row.nextHop)
        .value(_.snmpInput, row.snmpInput)
        .value(_.snmpOutput, row.snmpOutput)
        .value(_.srcMask, row.srcMask)
        .value(_.dstMask, row.dstMask)
      (count + 1, b.add(statement))
  }._2.future()
}

/**
 * NetFlow Version 6 Flow
 *
 * *-------*-----------*----------------------------------------------------------*
 * | Bytes | Contents  | Description                                              |
 * *-------*-----------*----------------------------------------------------------*
 * | 0-3   | srcaddr   | Source IP address                                        |
 * *-------*-----------*----------------------------------------------------------*
 * | 4-7   | dstaddr   | Destination IP address                                   |
 * *-------*-----------*----------------------------------------------------------*
 * | 8-11  | nexthop   | IP address of next hop senderIP                            |
 * *-------*-----------*----------------------------------------------------------*
 * | 12-13 | input     | Interface index (ifindex) of input interface             |
 * *-------*-----------*----------------------------------------------------------*
 * | 14-15 | output    | Interface index (ifindex) of output interface            |
 * *-------*-----------*----------------------------------------------------------*
 * | 16-19 | dPkts     | Packets in the flow                                      |
 * *-------*-----------*----------------------------------------------------------*
 * | 20-23 | dOctets   | Total number of Layer 3 bytes in the packets of the flow |
 * *-------*-----------*----------------------------------------------------------*
 * | 24-27 | First     | SysUptime at start of flow                               |
 * *-------*-----------*----------------------------------------------------------*
 * | 28-31 | Last      | SysUptime at the time the last packet of the flow was    |
 * |       |           | received                                                 |
 * *-------*-----------*----------------------------------------------------------*
 * | 32-33 | srcport   | TCP/UDP source port number or equivalent                 |
 * *-------*-----------*----------------------------------------------------------*
 * | 34-35 | dstport   | TCP/UDP destination port number or equivalent            |
 * *-------*-----------*----------------------------------------------------------*
 * | 36    | pad1      | Unused (zero) bytes                                      |
 * *-------*-----------*----------------------------------------------------------*
 * | 37    | tcpflags  | Cumulative OR of TCP flags                               |
 * *-------*-----------*----------------------------------------------------------*
 * | 38    | prot      | IP protocol type (for example, TCP = 6; UDP = 17)        |
 * *-------*-----------*----------------------------------------------------------*
 * | 39    | tos       | IP type of service (ToS)                                 |
 * *-------*-----------*----------------------------------------------------------*
 * | 40-41 | src_as    | AS number of the source, either origin or peer           |
 * *-------*-----------*----------------------------------------------------------*
 * | 42-43 | dst_as    | AS number of the destination, either origin or peer      |
 * *-------*-----------*----------------------------------------------------------*
 * | 44    | src_mask  | Source address prefix mask bits                          |
 * *-------*-----------*----------------------------------------------------------*
 * | 45    | dst_mask  | Destination address prefix mask bits                     |
 * *-------*-----------*----------------------------------------------------------*
 * | 46-51 | pad2      | Unused (zero) bytes                                      |
 * *-------*-----------*----------------------------------------------------------*
 */

sealed class NetFlowV6 extends CassandraTable[NetFlowV6, NetFlowV6Record] {

  /**
   * Parse a Version 6 Flow
   *
   * @param sender The sender's InetSocketAddress
   * @param buf Netty ByteBuf Slice containing the UDP Packet
   * @param fpId FlowPacket-UUID this Flow arrived on
   * @param uptime Millis since UNIX Epoch when the exporting device/sender booted
   * @param timestamp DateTime when this flow was exported
   * @param samplingInterval Interval samples are sent
   */
  def apply(sender: InetSocketAddress, buf: ByteBuf, fpId: UUID, uptime: Long, timestamp: DateTime, samplingInterval: Int): Option[NetFlowV6Record] =
    Try[NetFlowV6Record] {
      val sampling = NodeConfig.values.netflow.calculateSamples
      val pkts = buf.getUnsignedInteger(16, 4)
      val bytes = buf.getUnsignedInteger(20, 4)
      NetFlowV6Record(UUIDs.timeBased(), sender, buf.readableBytes(), uptime, timestamp,
        buf.getUnsignedInteger(32, 2).toInt, // srcPort
        buf.getUnsignedInteger(34, 2).toInt, // dstPort
        Option(buf.getUnsignedInteger(40, 2).toInt).filter(_ != -1), // srcAS
        Option(buf.getUnsignedInteger(42, 2).toInt).filter(_ != -1), // dstAS
        if (sampling) pkts * samplingInterval else pkts, // pkts
        if (sampling) bytes * samplingInterval else bytes, // bytes
        buf.getUnsignedByte(38).toInt, // proto
        buf.getUnsignedByte(39).toInt, // tos
        buf.getUnsignedByte(37).toInt, // tcpflags
        Some(buf.getUnsignedInteger(24, 4)).filter(_ != 0).map(x => timestamp.minus(uptime - x)), // start
        Some(buf.getUnsignedInteger(28, 4)).filter(_ != 0).map(x => timestamp.minus(uptime - x)), // stop
        buf.getInetAddress(0, 4), // srcAddress
        buf.getInetAddress(4, 4), // dstAddress
        Option(buf.getInetAddress(8, 4)).filter(_.getHostAddress != "0.0.0.0"), // nextHop
        buf.getUnsignedInteger(12, 2).toInt, // snmpInput
        buf.getUnsignedInteger(14, 2).toInt, // snmpOutput
        buf.getUnsignedByte(44).toInt, // srcMask
        buf.getUnsignedByte(45).toInt, // dstMask
        fpId)
    }.toOption

  object id extends UUIDColumn(this) with PartitionKey[UUID]
  object packet extends TimeUUIDColumn(this) with Index[UUID]
  object sender extends InetAddressColumn(this) with PrimaryKey[InetAddress]
  object timestamp extends DateTimeColumn(this) with PrimaryKey[DateTime]
  object uptime extends LongColumn(this)
  object senderPort extends IntColumn(this) with Index[Int]
  object length extends IntColumn(this)
  object srcPort extends IntColumn(this) with Index[Int]
  object dstPort extends IntColumn(this) with Index[Int]
  object srcAS extends OptionalIntColumn(this) with Index[Option[Int]]
  object dstAS extends OptionalIntColumn(this) with Index[Option[Int]]
  object pkts extends LongColumn(this)
  object bytes extends LongColumn(this)
  object proto extends IntColumn(this) with Index[Int]
  object tos extends IntColumn(this) with Index[Int]
  object tcpflags extends IntColumn(this)
  object start extends OptionalDateTimeColumn(this) with Index[Option[DateTime]]
  object stop extends OptionalDateTimeColumn(this) with Index[Option[DateTime]]
  object srcAddress extends InetAddressColumn(this) with Index[InetAddress]
  object dstAddress extends InetAddressColumn(this) with Index[InetAddress]
  object nextHop extends OptionalInetAddressColumn(this) with Index[Option[InetAddress]]
  object snmpInput extends IntColumn(this)
  object snmpOutput extends IntColumn(this)
  object srcMask extends IntColumn(this)
  object dstMask extends IntColumn(this)

  def fromRow(row: Row): NetFlowV6Record = NetFlowV6Record(id(row), new InetSocketAddress(sender(row), senderPort(row)),
    length(row), uptime(row), timestamp(row), srcPort(row), dstPort(row), srcAS(row), dstAS(row), pkts(row), bytes(row),
    proto(row), tos(row), tcpflags(row), start(row), stop(row), srcAddress(row), dstAddress(row), nextHop(row),
    snmpInput(row), snmpOutput(row), srcMask(row), dstMask(row), packet(row))
}

object NetFlowV6 extends NetFlowV6

case class NetFlowV6Record(id: UUID, sender: InetSocketAddress, length: Int, uptime: Long, timestamp: DateTime,
                           srcPort: Int, dstPort: Int, srcAS: Option[Int], dstAS: Option[Int],
                           pkts: Long, bytes: Long, proto: Int, tos: Int, tcpflags: Int,
                           start: Option[DateTime], stop: Option[DateTime],
                           srcAddress: InetAddress, dstAddress: InetAddress, nextHop: Option[InetAddress],
                           snmpInput: Int, snmpOutput: Int, srcMask: Int, dstMask: Int, packet: UUID)
  extends NetFlowData[NetFlowV6Record] {
  def version = "NetFlowV6"

  override lazy val jsonExtra = ("srcMask" -> srcMask) ~ ("dstMask" -> dstMask) ~
    ("snmp" -> ("input" -> snmpInput) ~ ("output" -> snmpOutput))
}
