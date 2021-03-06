package project4

import java.io.FileWriter

import scala.io.Source
import scala.collection.mutable.ListBuffer

import org.scalaquery.session._
import org.scalaquery.session.Database.threadLocalSession

import org.scalaquery.simple.{StaticQuery => Q}

// Import the query language
import org.scalaquery.ql._
import org.scalaquery.ql.Ordering.Desc

// Import the standard SQL types
import org.scalaquery.ql.TypeMapper._

// Use H2Driver which implements ExtendedProfile and thus requires ExtendedTables
import org.scalaquery.ql.extended.SQLiteDriver.Implicit._
import org.scalaquery.ql.extended.{ExtendedTable => Table}

trait Analog {
  private def splitTrim(str: String, delimiter: String) = str.split(delimiter).map(_ trim).toList
  private def trimtoInt(str: String) = str.stripPrefix("(").stripPrefix("[").stripSuffix(")").stripSuffix("]") toInt
  private val dirFiles = new java.io.File(".").listFiles.filter(_.getName.endsWith(".log"))
  
  object Receiving {
    def unapply(str: String): Option[(Int, Int, Int, String)] = {
      splitTrim(str, "<-") match {
        case receiverStr :: senderStr :: Nil =>
          (splitTrim(receiverStr, ":"), splitTrim(senderStr, ":")) match {
            case (time :: receiver :: Nil,
            	  sender :: message :: Nil) => Some(trimtoInt(time), trimtoInt(receiver), trimtoInt(sender), message)
            case _ => None  // should not happen according to log format
          }
        case _ => None
      }
    }
  }

  object Sending {
    def unapply(str: String): Option[(Int, Int, Int, String)] = {
      splitTrim(str, "->") match {
        case senderStr :: receiverStr :: Nil =>
          (splitTrim(senderStr, ":"), splitTrim(receiverStr, ":")) match {
            case (time :: sender :: Nil,
            	  receiver :: message :: Nil) => Some(trimtoInt(time), trimtoInt(sender), trimtoInt(receiver), message)
            case _ => None  // should not happen according to log format
          }
        case _ => None
      }
    }
  }
  
  def tuples = {
    val sendBuffer = new ListBuffer[(Int,Int,Int,String)]()
    val receiveBuffer = new ListBuffer[(Int,Int,Int,String)]()
    for {
	  file <- dirFiles
	  fileName = file.getName
	  line <- Source.fromFile(fileName).getLines()
	} {
	  line match {
	    case Sending(time, sender, receiver, message) if (message=="BadRumor") => sendBuffer += ((time, sender, receiver, message))
	    case Receiving(time, receiver, sender, message) if (message=="BadRumor") => receiveBuffer += ((time, receiver, sender, message))
	    case _ => ;
	  }
    }
	(sendBuffer toList, receiveBuffer toList)
  }
}

trait Db {
  val sendLogs = new Table[(Int, Int, Int, String)]("sendLogs") {
    def time = column[Int]("time") // This is the primary key column
    def sender = column[Int]("sender")
    def receiver = column[Int]("receiver")
    def msg = column[String]("msg")
    // Every table needs a * projection with the same type as the table's type parameter
    def * = time ~ sender ~ receiver ~ msg
    def logkey = primaryKey("key", time ~ sender ~ receiver)
  }
  val receiveLogs = new Table[(Int, Int, Int, String)]("receiveLogs") {
    def time = column[Int]("time") // This is the primary key column
    def receiver = column[Int]("receiver")
    def sender = column[Int]("sender")
    def msg = column[String]("msg")
    // Every table needs a * projection with the same type as the table's type parameter
    def * = time ~ receiver ~ sender ~ msg
    def logkey = primaryKey("key", time ~ receiver ~ sender)
  }
}

class Detector(from: Int) extends Analog with Db {
    private val dbName = "logs"+from
    private val mscFile = "msg.msc"
    private def using[A <: {def close(): Unit}, B](param: A)(f: A => B): B =
      try { f(param) } finally { param.close() }
    private def writeMsc(data: String, append: Boolean = true) =
      using (new FileWriter(mscFile, append)) (_.write(data))

    private def createDb() {
      (sendLogs.ddl ++ receiveLogs.ddl).create
      val (sendTuples, receiveTuples) = tuples
      sendLogs.insertAll(sendTuples: _*)
      receiveLogs.insertAll(receiveTuples: _*)
    }

    private def dropDb() { (sendLogs.ddl ++ receiveLogs.ddl).drop }
    private def genMsg(sender: Int, receiver: Int) = sender+"=>>"+receiver+" [ label = \"message\" ];\n"

    @annotation.tailrec
    private def tracerec(traceNode: Int, traceTime: Int, resultList: List[(Int, Int)] = List()): List[(Int, Int)] = {
      if (traceNode<=0) resultList
      else {
        val previousSenders = for {
	      receiving <- receiveLogs if receiving.receiver === traceNode && receiving.time <= traceTime
	      _ <- Query orderBy(Desc(receiving.time))
	    } yield receiving.sender ~ receiving.time

	    previousSenders.list match {
	      case Nil => resultList
	      case (sender, time) :: rest =>
	        if (sender==0) { println("Malicious node: " +traceNode); resultList }
	        else tracerec(sender, time, (sender, traceNode) :: resultList)
	    }
      }
    }

    private def allMsgs = {
      val entities = scala.collection.mutable.Set[Int]()
      val sends = for {
        sending <- sendLogs
        _ <- Query orderBy(sending.time)
      } yield sending.sender ~ sending.receiver
      sends foreach { case (s,r) => entities+=(s,r) }
      (sends.list, entities)
    }

    private def failures = {
      val sql2 = 
"""SELECT DISTINCT S1.receiver
   FROM sendLogs S1
   WHERE NOT EXISTS (
     SELECT *
     FROM sendLogs S2
     WHERE S2.sender = S1.receiver
     AND S2.time > S1.time
         UNION
     SELECT *
     FROM receiveLogs R2
     WHERE R2.receiver = S1.receiver
     AND R2.time > S1.time
   )
   ORDER BY S1.receiver ASC"""
      val q = Q[Int] + sql2
      q foreach { println }
    }

    def trace() = Database.forURL("jdbc:sqlite:"+dbName+".db", driver = "org.sqlite.JDBC") withSession {
      createDb()
      failures
      writeMsc("""msc {
  hscale = "1";
""", false)
      val (allMessages, entities) = allMsgs
      writeMsc(entities.mkString(",")+";")
      allMessages foreach { case (s,r) => writeMsc (genMsg(s,r)) }
      writeMsc("---;")
      val traceLog = tracerec(from, Int.MaxValue)
      traceLog foreach { case (s,r) => writeMsc (genMsg(s,r))}
      writeMsc("\n}\n")
      //dropDb()
    }
}