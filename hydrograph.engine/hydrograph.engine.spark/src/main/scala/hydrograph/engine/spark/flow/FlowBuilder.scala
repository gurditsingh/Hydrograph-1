/*******************************************************************************
 * Copyright 2017 Capital One Services, LLC and Bitwise, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package hydrograph.engine.spark.flow

import hydrograph.engine.jaxb.commontypes._
import hydrograph.engine.jaxb.main.Graph
import hydrograph.engine.spark.components.adapter.ExecutionTrackingAdapter
import hydrograph.engine.spark.components.adapter.base.{RunProgramAdapterBase, _}
import hydrograph.engine.spark.components.base.{ComponentParameterBuilder, SparkFlow}
import hydrograph.engine.spark.components.platform.BaseComponentParams
import hydrograph.engine.spark.execution.tracking.PartitionStageAccumulator
import org.apache.spark.sql.DataFrame
import org.apache.spark.storage.StorageLevel

import scala.collection.JavaConverters._
import scala.collection.mutable
/**
  * The Class FlowBuilder.
  *
  * @author Bitwise
  *
  */

class FlowBuilder(runtimeContext: RuntimeContext) {


  def buildFlow(): mutable.LinkedHashSet[SparkFlow] ={
    val flow=new mutable.LinkedHashSet[SparkFlow]()
    for(batch<- runtimeContext.traversal.getFlowsNumber.asScala){
     flow ++= createAndConnect(batch)
    }

    flow
  }


  def createAndConnect(batch:String): mutable.LinkedHashSet[SparkFlow] = {
    val flow=new mutable.LinkedHashSet[SparkFlow]()
    val outLinkMap = new mutable.HashMap[String, Map[String,DataFrame]]()


    runtimeContext.traversal.getOrderedComponentsList(batch).asScala.map(compID=>{

      runtimeContext.adapterFactory.getAdapterMap()(compID) match {

        case inputAdapterBase:InputAdapterBase =>
          val baseComponentParams = ComponentParameterBuilder(compID, runtimeContext, outLinkMap,new BaseComponentParams())
            .setSparkSession(runtimeContext.sparkSession).build()
          inputAdapterBase.createComponent(baseComponentParams)
          val inputDataFrame= inputAdapterBase.getComponent().createComponent()
          setCacheLevel(extractRuntimeProperties(compID,runtimeContext.hydrographJob.getJAXBObject),inputDataFrame)
          outLinkMap +=(compID -> inputDataFrame)

        case executionTrackingAdapter:ExecutionTrackingAdapter =>
          val baseComponentParams = ComponentParameterBuilder(compID, runtimeContext, outLinkMap,new BaseComponentParams())
            .setInputDataFrame().setSparkSession(runtimeContext.sparkSession).setInputDataFrameWithCompID().setInputSchemaFieldsWithCompID()
            .setOutputSchemaFields().setInputSchemaFields().build()
          executionTrackingAdapter.createComponent(baseComponentParams)
          val opDataFrame= executionTrackingAdapter.getComponent().createComponent()
          outLinkMap +=(compID -> opDataFrame)

        case operationAdapterBase:OperationAdapterBase =>
          val baseComponentParams = ComponentParameterBuilder(compID, runtimeContext, outLinkMap,new BaseComponentParams())
            .setInputDataFrame().setSparkSession(runtimeContext.sparkSession).setInputDataFrameWithCompID().setInputSchemaFieldsWithCompID()
            .setOutputSchemaFieldsForOperation().setInputSchemaFields().build()
          operationAdapterBase.createComponent(baseComponentParams)
          val opDataFrame= operationAdapterBase.getComponent().createComponent()
          setCacheLevel(extractRuntimeProperties(compID,runtimeContext.hydrographJob.getJAXBObject),opDataFrame)
          outLinkMap +=(compID -> opDataFrame)

        case straightPullAdapterBase:StraightPullAdapterBase =>
          val   baseComponentParams = ComponentParameterBuilder(compID, runtimeContext, outLinkMap,new BaseComponentParams())
            .setInputDataFrame().setOutputSchemaFields().setInputSchemaFields().build()
          straightPullAdapterBase.createComponent(baseComponentParams)
          val opDataFrame= straightPullAdapterBase.getComponent().createComponent()
          setCacheLevel(extractRuntimeProperties(compID,runtimeContext.hydrographJob.getJAXBObject),opDataFrame)
          outLinkMap +=(compID -> opDataFrame)

        case outputAdapterBase:OutputAdapterBase =>
          val  baseComponentParams = ComponentParameterBuilder(compID, runtimeContext, outLinkMap,new BaseComponentParams())
            .setSparkSession(runtimeContext.sparkSession).setInputDataFrame().build()
          outputAdapterBase.createComponent(baseComponentParams)
          flow += outputAdapterBase.getComponent()
          outputAdapterBase.getComponent().setSparkFlowName(compID)

        case runProgramAdapterBase:RunProgramAdapterBase =>
          val baseComponentParams = ComponentParameterBuilder(compID, runtimeContext, outLinkMap, new BaseComponentParams())
            .build()
          runProgramAdapterBase.createComponent(baseComponentParams)
          flow += runProgramAdapterBase.getComponent()
          runProgramAdapterBase.getComponent().setSparkFlowName(compID)
      }
    })

flow
  }

  private def extractRuntimeProperties(compId:String,graph: Graph): TypeProperties ={
    val jaxbObject=graph.getInputsOrOutputsOrStraightPulls.asScala

    jaxbObject.filter(c=>c.getId.equals(compId)).head match {
      case op: TypeInputComponent =>
        op.asInstanceOf[TypeInputComponent].getRuntimeProperties
      case op: TypeOutputComponent =>
        op.asInstanceOf[TypeOutputComponent].getRuntimeProperties
      case st: TypeStraightPullComponent =>
        st.asInstanceOf[TypeStraightPullComponent].getRuntimeProperties
      case op: TypeOperationsComponent =>
        op.asInstanceOf[TypeOperationsComponent].getRuntimeProperties

    }
  }

  private def setCacheLevel(typeProperty: TypeProperties,dataFrameMap:Map[String,DataFrame]): Unit ={
    val splitter=":"
    if(typeProperty!= null && typeProperty.getProperty!=null){
      typeProperty.getProperty.asScala.filter(tp=> tp.getName.equalsIgnoreCase("cache")).foreach(tp=>{
        if(tp.getValue.contains(splitter)) {
          val storageType = tp.getValue.trim.substring(tp.getValue.trim.lastIndexOf(splitter) + 1, tp.getValue.trim.length)
          val outSocketId = tp.getValue.trim.substring(0, tp.getValue.trim.lastIndexOf(splitter))

          storageType match {
            case s if s.equalsIgnoreCase("disk_only") => dataFrameMap(outSocketId).persist(StorageLevel.DISK_ONLY)
            case s if s.equalsIgnoreCase("disk_only_2") => dataFrameMap(outSocketId).persist(StorageLevel.DISK_ONLY_2)
            case s if s.equalsIgnoreCase("memory_only") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_ONLY)
            case s if s.equalsIgnoreCase("memory_only_2") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_ONLY_2)
            case s if s.equalsIgnoreCase("memory_only_ser") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_ONLY_SER)
            case s if s.equalsIgnoreCase("memory_only_ser_2") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_ONLY_SER_2)
            case s if s.equalsIgnoreCase("memory_and_disk") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_AND_DISK)
            case s if s.equalsIgnoreCase("memory_and_disk_2") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_AND_DISK_2)
            case s if s.equalsIgnoreCase("memory_and_disk_ser") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_AND_DISK_SER)
            case s if s.equalsIgnoreCase("memory_and_disk_ser_2") => dataFrameMap(outSocketId).persist(StorageLevel.MEMORY_AND_DISK_SER_2)
          }

        }
        else{
          throw new RuntimeException("Cache Property value should be in proper format eg: outSocketID : One of \"disk,memory,memory_and_disk\", But user provides: "+ tp.getValue)
        }
      })
    }
  }
}

object FlowBuilder {
  def apply(runtimeContext: RuntimeContext): FlowBuilder = {
    new FlowBuilder(runtimeContext)
  }
}
