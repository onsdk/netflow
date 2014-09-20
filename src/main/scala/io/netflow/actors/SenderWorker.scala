package io.netflow.actors

import java.net.{ InetAddress, InetSocketAddress }
import java.util.concurrent.atomic.AtomicReference

import io.netflow.flows._
import io.netflow.lib._
import io.wasted.util._
import org.joda.time.DateTime

import com.websudos.phantom.Implicits._
import scala.concurrent.duration._

private[netflow] class SenderWorker(sender: InetAddress) extends Wactor with Logger {
  override protected def loggerName = "Sender " + sender.getHostAddress
  info("Starting for " + loggerName)

  private[actors] val senderPrefixes = new AtomicReference(List.empty[InetPrefix])
  private[actors] val thruputPrefixes = new AtomicReference(List.empty[InetPrefix])

  private var templateCache = Map.empty[Int, cflow.Template]
  def templates = templateCache
  def setTemplate(tmpl: cflow.Template): Unit = {
    templateCache += tmpl.id -> tmpl
    tmpl match {
      case nf9: cflow.NetFlowV9TemplateRecord =>
        cflow.NetFlowV9Template.update.where(_.id eqs nf9.id).and(_.sender eqs tmpl.sender).
          modify(_.senderPort setTo tmpl.senderPort).
          and(_.last setTo DateTime.now).
          and(_.map setTo tmpl.map).future()
      //case nf10: cflow.NetFlowV10TemplateRecord => FIXME Netflow 10
    }
  }

  private var cancellable = Shutdown.schedule()

  private def handleFlowPacket(osender: InetSocketAddress, handled: Option[FlowPacket]) = handled match {
    case Some(fp) =>
      fp.persist
      FlowSender.update.where(_.ip eqsToken sender).
        modify(_.last setTo Some(DateTime.now)).
        and(_.flows increment fp.count).future()
      FlowManager.save(osender, fp, senderPrefixes.get, thruputPrefixes.get)
    case _ =>
      warn("Unable to parse FlowPacket")
      FlowManager.bad(osender)
  }

  def receive = {
    case NetFlow(osender, buf) =>
      Shutdown.avoid()
      val handled: Option[FlowPacket] = {
        Tryo(buf.getUnsignedShort(0)) match {
          case Some(1) => cflow.NetFlowV1Packet(osender, buf).toOption
          case Some(5) => cflow.NetFlowV5Packet(osender, buf).toOption
          case Some(6) => cflow.NetFlowV6Packet(osender, buf).toOption
          case Some(7) => cflow.NetFlowV7Packet(osender, buf).toOption
          case Some(9) =>
            val nf9templates = templateCache.
              filter(_._2.isInstanceOf[cflow.NetFlowV9TemplateRecord]).
              map(x => (x._1, x._2.asInstanceOf[cflow.NetFlowV9TemplateRecord]))
            cflow.NetFlowV9Packet(osender, buf, this).toOption
          case Some(10) => None //Some(cflow.NetFlowV10Packet(sender, buf))
          case _ => None
        }
      }
      buf.release()
      handleFlowPacket(osender, handled)

    case SFlow(osender, buf) =>
      Shutdown.avoid()
      if (buf.readableBytes < 28) {
        warn("Unable to parse FlowPacket")
        FlowManager.bad(osender)
      } else {
        val handled: Option[FlowPacket] = {
          Tryo(buf.getLong(0)) match {
            case Some(3) =>
              info("We do not handle sFlow v3 yet"); None // sFlow 3
            case Some(4) =>
              info("We do not handle sFlow v4 yet"); None // sFlow 4
            case Some(5) =>
              //sflow.SFlowV5Packet(sender, buf)
              info("We do not handle sFlow v5 yet"); None // sFlow 5
            case _ => None
          }
        }
        handleFlowPacket(osender, handled)
      }
      buf.release()

    case Shutdown =>
      SenderManager.removeActorFor(sender)
      templateCache = Map.empty
      this ! Wactor.Die
  }

  private case object Shutdown {
    def schedule() = scheduleOnce(Shutdown, 5.minutes)
    def avoid() {
      cancellable.cancel()
      cancellable = schedule()
    }
  }
}
