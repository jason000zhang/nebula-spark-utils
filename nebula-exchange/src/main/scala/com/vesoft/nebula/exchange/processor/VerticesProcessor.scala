/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License,
 * attached with Common Clause Condition 1.0, found in the LICENSES directory.
 */

package com.vesoft.nebula.exchange.processor

import com.vesoft.nebula.exchange.{
  ErrorHandler,
  GraphProvider,
  MetaProvider,
  Vertex,
  Vertices,
  VidType
}
import com.vesoft.nebula.exchange.config.{
  Configs,
  SinkCategory,
  StreamingDataSourceConfigEntry,
  TagConfigEntry
}
import com.vesoft.nebula.exchange.utils.NebulaUtils
import com.vesoft.nebula.exchange.writer.NebulaGraphClientWriter
import org.apache.log4j.Logger
import org.apache.spark.TaskContext
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.{DataFrame, Encoders}
import org.apache.spark.util.LongAccumulator

import scala.collection.mutable.ArrayBuffer

/**
  *
  * @param data
  * @param tagConfig
  * @param fieldKeys
  * @param nebulaKeys
  * @param config
  * @param batchSuccess
  * @param batchFailure
  */
class VerticesProcessor(data: DataFrame,
                        tagConfig: TagConfigEntry,
                        fieldKeys: List[String],
                        nebulaKeys: List[String],
                        config: Configs,
                        batchSuccess: LongAccumulator,
                        batchFailure: LongAccumulator)
    extends Processor {

  @transient
  private[this] lazy val LOG = Logger.getLogger(this.getClass)

  private def processEachPartition(iterator: Iterator[Vertex]): Unit = {
    val graphProvider = new GraphProvider(config.databaseConfig.getGraphAddress)

    val writer = new NebulaGraphClientWriter(config.databaseConfig,
                                             config.userConfig,
                                             config.connectionConfig,
                                             config.executionConfig.retry,
                                             config.rateConfig,
                                             tagConfig,
                                             graphProvider)

    val errorBuffer = ArrayBuffer[String]()

    writer.prepare()
    // batch write tags
    iterator.grouped(tagConfig.batch).foreach { vertex =>
      val vertices      = Vertices(nebulaKeys, vertex.toList, tagConfig.vertexPolicy)
      val failStatement = writer.writeVertices(vertices)
      if (failStatement == null) {
        batchSuccess.add(1)
      } else {
        errorBuffer.append(failStatement)
        batchFailure.add(1)
      }
    }
    if (errorBuffer.nonEmpty) {
      ErrorHandler.save(
        errorBuffer,
        s"${config.errorConfig.errorPath}/${tagConfig.name}.${TaskContext.getPartitionId()}")
      errorBuffer.clear()
    }
    writer.close()
    graphProvider.close()
  }

  override def process(): Unit = {

    val address = config.databaseConfig.getMetaAddress
    val space   = config.databaseConfig.space

    val metaProvider    = new MetaProvider(address)
    val fieldTypeMap    = NebulaUtils.getDataSourceFieldType(tagConfig, space, metaProvider)
    val isVidStringType = metaProvider.getVidType(space) == VidType.STRING

    if (tagConfig.dataSinkConfigEntry.category == SinkCategory.SST) {} else {
      val vertices = data
        .map { row =>
          val vertexID = {
            val index = row.schema.fieldIndex(tagConfig.vertexField)
            if (tagConfig.vertexPolicy.isEmpty) {
              // process string type vid
              if (isVidStringType) {
                val value = row.get(index).toString
                NebulaUtils.escapeUtil(value).mkString("\"", "", "\"")
              } else {
                // process int type vid
                assert(NebulaUtils.isNumic(row.get(index).toString))
                row.get(index).toString
              }
            } else {
              row.get(index).toString
            }
          }

          val values = for {
            property <- fieldKeys if property.trim.length != 0
          } yield extraValue(row, property, fieldTypeMap)
          Vertex(vertexID, values)
        }(Encoders.kryo[Vertex])

      // streaming write
      if (data.isStreaming) {
        val streamingDataSourceConfig =
          tagConfig.dataSourceConfigEntry.asInstanceOf[StreamingDataSourceConfigEntry]
        vertices.writeStream
          .foreachBatch((vertexSet, batchId) => {
            LOG.info(s"${tagConfig.name} tag start batch ${batchId}.")
            vertexSet.foreachPartition(processEachPartition _)
          })
          .trigger(Trigger.ProcessingTime(s"${streamingDataSourceConfig.intervalSeconds} seconds"))
          .start()
          .awaitTermination()
      } else
        vertices.foreachPartition(processEachPartition _)
    }
  }
}
