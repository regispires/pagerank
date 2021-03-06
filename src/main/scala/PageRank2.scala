import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.graphx.Edge
import org.apache.spark.graphx.EdgeRDD
import org.apache.spark.graphx.Graph
import org.apache.spark.graphx.GraphLoader
import org.apache.spark.graphx.PartitionStrategy
import org.apache.spark.graphx.VertexRDD
import org.apache.hadoop.io.compress.GzipCodec
import org.apache.spark.Logging

object PageRank2 extends Serializable with Logging {
  def main(args: Array[String]) {
    logInfo("args: " + args.mkString(" "))
    val src = args(0)
    val parts = args(1).toInt
    
    val conf = new SparkConf().setAppName("PageRank2")
    val master = System.getProperty("master")
    if (master != null) {
      // VM Arguments: -Dmaster=local
      conf.setMaster(master)
    }
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.task.maxFailures", "1")
    conf.set("spark.speculation", "false")
    //conf.set("spark.shuffle.manager", "SORT")
    // Compress RDD in both memory and on-disk using the fast Snappy compression codec
    conf.set("spark.rdd.compress", "true")
    conf.set("spark.io.compression.codec", "snappy")
    conf.set("spark.storage.memoryFraction", "0.4")
    conf.set("spark.shuffle.memoryFraction", "0.1")
    //
    //conf.set("spark.eventLog.enabled", "true")
    // Help prevent FetchFailed exceptions when single node is heavily taxed
    // http://stackoverflow.com/questions/26247654/spark-fail-to-run-the-terasort-when-the-amount-of-data-gets-bigger
    conf.set("spark.core.connection.ack.wait.timeout", "180")
    // Attempting to fix "Map output statuses were 10810591 bytes which exceeds spark.akka.frameSize (10485760 bytes)"
    conf.set("spark.akka.frameSize", "42")

    val startTime = System.nanoTime()
    val sc = new SparkContext(conf)
    if (master != null && master.equals("local")) {
      // if master is different from local, use fs.defaultFS property from core-site.xml
      sc.hadoopConfiguration.set("fs.defaultFS", "file:///")
    }

    var endTime = System.nanoTime()    
    logInfo("Spark context loaded: " + ((endTime - startTime) / 1000000000d))

    
    val cacheType = StorageLevel.MEMORY_AND_DISK_SER

    // Only repartition if it's local as otherwise there's large amounts of network communication
    val lines = sc.textFile(src, parts)
    endTime = System.nanoTime()    
    logInfo("Get lines: " + ((endTime - startTime) / 1000000000d))

    // Remove any lines which are comments
    val filtered_lines = lines.repartition(parts).filter(line => !line.startsWith("#") && !line.startsWith("%"))
    // Remove any lines starting with # and then split according to tabs
    val edgeList = filtered_lines.map { line =>
      val fields = line.split("\t")
      //val fields = line.split(" ")
      (fields(0).toLong, fields(1).toLong)
    }.map(e => Edge(e._1, e._2, 0)) //.persist(StorageLevel.DISK_ONLY)
    endTime = System.nanoTime()    
    logInfo("Get edge list: " + ((endTime - startTime) / 1000000000d))

    val edgeRDD = EdgeRDD.fromEdges(edgeList)
    endTime = System.nanoTime()    
    logInfo("Get edge RDD: " + ((endTime - startTime) / 1000000000d))

    val graph = Graph.fromEdges(edgeRDD, defaultValue = 0, cacheType, cacheType).partitionBy(PartitionStrategy.RandomVertexCut, parts)
    //edgeList.unpersist()
    endTime = System.nanoTime()    
    logInfo("Graph loaded: " + ((endTime - startTime) / 1000000000d))
    logInfo("Vertices: " + graph.vertices.count)
    logInfo("Edges: " + graph.edges.count)

    // Run PageRank
    val ranks = graph.staticPageRank(numIter = 30).vertices
    endTime = System.nanoTime()    
    logInfo("PageRank executed: " + ((endTime - startTime) / 1000000000d))
    // Print PageRank result
    //println(ranks.collect.toSeq.sortBy(_._2).mkString("\n"))
    //ranks.saveAsTextFile("/tmp/rankOutGzip/", classOf[GzipCodec])
    ranks.saveAsTextFile("/tmp/scala-output-" + DateUtils.format())
    endTime = System.nanoTime()    
    logInfo("Results saved: " + ((endTime - startTime) / 1000000000d))
  }
}