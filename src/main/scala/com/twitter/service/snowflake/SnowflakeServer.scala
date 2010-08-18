/** Copyright 2010 Twitter, Inc. */
package com.twitter.service.snowflake

import com.twitter.service.snowflake.client.SnowflakeClient
import com.twitter.service.snowflake.gen._
import org.apache.thrift.{TException, TProcessor, TProcessorFactory}
import org.apache.thrift.protocol.{TBinaryProtocol, TProtocol, TProtocolFactory}
import org.apache.thrift.transport._
import org.apache.thrift.server.{THsHaServer, TServer, TThreadPoolServer}
import net.lag.configgy.{Config, Configgy, RuntimeEnvironment}
import net.lag.logging.Logger
import scala.tools.nsc.MainGenericRunner
import com.twitter.zookeeper._
import org.apache.zookeeper.ZooDefs.Ids
import org.apache.zookeeper.data.{ACL, Id}
import org.apache.zookeeper.CreateMode._
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.{CreateMode, Watcher, WatchedEvent}
import scala.collection.mutable
import scala.util.Sorting
import java.net.InetAddress

case class Peer(hostname: String, port: Int)

object SnowflakeServer {
  private val log = Logger.get
  val runtime = new RuntimeEnvironment(getClass)
  var server: TServer = null
  var datacenterId: Int = -1
  lazy val datacenterIdPath: String = Configgy.config("snowflake.datacenter_id_path")
  var workerId: Int = -1
  val workers = new mutable.ListBuffer[IdWorker]()
  lazy val PORT = Configgy.config("snowflake.server_port").toInt
  lazy val zkPath = Configgy.config("snowflake.worker_id_path")
  lazy val zkWatcher = new ZKWatch((a: WatchedEvent) => {})
  lazy val hostlist = Configgy.config("zookeeper-client.hostlist")
  lazy val zkClient = {
    log.info("Creating ZooKeeper client connected to %s", hostlist)
    new ZooKeeperClient(Configgy.config, zkWatcher)
  }

  def shutdown(): Unit = {
    if (server != null) {
      log.info("Shutting down.")
      server.stop()
      server = null
    }
  }

  def main(args: Array[String]) {
    runtime.load(args)

    if (!Configgy.config("snowflake.skip_sanity_checks").toBoolean) {
      sanityCheckPeers()
    }

    loadDatacenterId()
    workerId = loadWorkerId()
    val admin = new AdminService(Configgy.config, runtime)

    Thread.sleep(Configgy.config("snowflake.startup_sleep_ms").toLong)

    try {
      val worker = new IdWorker(workerId, datacenterId)
      workers += worker
      log.info("snowflake.server_port loaded: %s", PORT)

      val processor = new Snowflake.Processor(worker)
      val transport = new TNonblockingServerSocket(PORT)
      val serverOpts = new THsHaServer.Options
      serverOpts.minWorkerThreads = Configgy.config("snowflake.thrift-server-threads-min").toInt
      serverOpts.maxWorkerThreads = Configgy.config("snowflake.thrift-server-threads-max").toInt

      val server = new THsHaServer(processor, transport, serverOpts)

      log.info("Starting server on port %s with minWorkerThreads=%s and maxWorkerThreads=%s", PORT, serverOpts.minWorkerThreads, serverOpts.maxWorkerThreads)
      server.serve()
    } catch {
      case e: Exception => {
        log.error(e, "Unexpected exception while initializing server: %s", e.getMessage)
        throw e
      }
    }
  }

  def loadDatacenterId() {
    datacenterId = Configgy.config("snowflake.datacenter_id", -1)
    if (datacenterId < 0) {
      datacenterId = (new String(zkClient.get(datacenterIdPath))).toInt
    }
  }

  def loadWorkerId(): Int = {
    val id = Configgy.config("worker_id", -1)

    if (id > 0) {
      return id
    }

    for (i <- 0 until 31) {
      try {
        log.info("trying to claim workerId %d", i)
        zkClient.create("%s/%s".format(zkPath, i), (getHostname + ':' + PORT).getBytes(), EPHEMERAL)
        log.info("successfully claimed workerId %d", i)
        return i
      } catch {
        case e: KeeperException => log.info("workerId collision, retrying")
      }
    }
    return -1
  }

  def peers(): mutable.HashMap[Int, Peer] = {
    var peerMap = new mutable.HashMap[Int, Peer]
    try {
      zkClient.get(zkPath)
    } catch {
      case _ => {
        log.info("%s missing, trying to create it", zkPath)
        zkClient.create(zkPath, Array(), PERSISTENT)
      }
    }

    val children = zkClient.getChildren(zkPath)
    children.foreach { i =>
      val peer = zkClient.get("%s/%s".format(zkPath, i))
      val list = new String(peer).split(':')
      peerMap(i.toInt) = new Peer(new String(list(0)), list(1).toInt)
    }
    log.info("found %s children".format(children.length))

    return peerMap
  }

  def sanityCheckPeers() {
    var peerCount = 0L
    val timestamps = peers().map { case (workerId: Int, peer: Peer) =>
      try {
        log.info("connecting to %s:%s".format(peer.hostname, peer.port))
        var (t, c) = SnowflakeClient.create(peer.hostname, peer.port, 1000)
        val reportedWorkerId = c.get_worker_id().toLong
        if (reportedWorkerId != workerId) {
          log.error("Worker at %s:%s has id %d in zookeeper, but via rpc it says %d", peer.hostname, peer.port, workerId, reportedWorkerId)
          throw new IllegalStateException("Worker id insanity.")
        }
        peerCount = peerCount + 1
        c.get_timestamp().toLong
      } catch {
        case e: TTransportException => {
          log.error("Couldn't talk to peer %s at %s:%s", workerId, peer.hostname, peer.port)
          throw e
        }
      }
    }

    if (timestamps.toSeq.size > 0) { // only run if peers exist
      val avg = timestamps.foldLeft(0L)(_ + _) / peerCount
      if (Math.abs(System.currentTimeMillis - avg) > 10000) {
        log.error("Timestamp sanity check failed. Mean timestamp is %d, but mine is %d, " +
                  "so I'm more than 10s away from the mean", avg, System.currentTimeMillis)
        throw new IllegalStateException("timestamp sanity check failed")
      }
    }
  }

  def getHostname(): String = InetAddress.getLocalHost().getHostName()

}
