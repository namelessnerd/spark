/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.api.python

import java.nio.ByteOrder

import scala.collection.JavaConversions._
import scala.util.Failure
import scala.util.Try

import net.razorvine.pickle.{Unpickler, Pickler}

import org.apache.spark.{Logging, SparkException}
import org.apache.spark.rdd.RDD

/** Utilities for serialization / deserialization between Python and Java, using Pickle. */
private[python] object SerDeUtil extends Logging {
  // Unpickle array.array generated by Python 2.6
  class ArrayConstructor extends net.razorvine.pickle.objects.ArrayConstructor {
    //  /* Description of types */
    //  static struct arraydescr descriptors[] = {
    //    {'c', sizeof(char), c_getitem, c_setitem},
    //    {'b', sizeof(char), b_getitem, b_setitem},
    //    {'B', sizeof(char), BB_getitem, BB_setitem},
    //    #ifdef Py_USING_UNICODE
    //      {'u', sizeof(Py_UNICODE), u_getitem, u_setitem},
    //    #endif
    //    {'h', sizeof(short), h_getitem, h_setitem},
    //    {'H', sizeof(short), HH_getitem, HH_setitem},
    //    {'i', sizeof(int), i_getitem, i_setitem},
    //    {'I', sizeof(int), II_getitem, II_setitem},
    //    {'l', sizeof(long), l_getitem, l_setitem},
    //    {'L', sizeof(long), LL_getitem, LL_setitem},
    //    {'f', sizeof(float), f_getitem, f_setitem},
    //    {'d', sizeof(double), d_getitem, d_setitem},
    //    {'\0', 0, 0, 0} /* Sentinel */
    //  };
    // TODO: support Py_UNICODE with 2 bytes
    // FIXME: unpickle array of float is wrong in Pyrolite, so we reverse the
    // machine code for float/double here to workaround it.
    // we should fix this after Pyrolite fix them
    val machineCodes: Map[Char, Int] = if (ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
      Map('c' -> 1, 'B' -> 0, 'b' -> 1, 'H' -> 3, 'h' -> 5, 'I' -> 7, 'i' -> 9,
        'L' -> 11, 'l' -> 13, 'f' -> 14, 'd' -> 16, 'u' -> 21
      )
    } else {
      Map('c' -> 1, 'B' -> 0, 'b' -> 1, 'H' -> 2, 'h' -> 4, 'I' -> 6, 'i' -> 8,
        'L' -> 10, 'l' -> 12, 'f' -> 15, 'd' -> 17, 'u' -> 20
      )
    }
    override def construct(args: Array[Object]): Object = {
      if (args.length == 1) {
        construct(args ++ Array(""))
      } else if (args.length == 2 && args(1).isInstanceOf[String]) {
        val typecode = args(0).asInstanceOf[String].charAt(0)
        val data: Array[Byte] = args(1).asInstanceOf[String].getBytes("ISO-8859-1")
        construct(typecode, machineCodes(typecode), data)
      } else {
        super.construct(args)
      }
    }
  }

  def initialize() = {
    Unpickler.registerConstructor("array", "array", new ArrayConstructor())
  }

  private def checkPickle(t: (Any, Any)): (Boolean, Boolean) = {
    val pickle = new Pickler
    val kt = Try {
      pickle.dumps(t._1)
    }
    val vt = Try {
      pickle.dumps(t._2)
    }
    (kt, vt) match {
      case (Failure(kf), Failure(vf)) =>
        logWarning(s"""
               |Failed to pickle Java object as key: ${t._1.getClass.getSimpleName}, falling back
               |to 'toString'. Error: ${kf.getMessage}""".stripMargin)
        logWarning(s"""
               |Failed to pickle Java object as value: ${t._2.getClass.getSimpleName}, falling back
               |to 'toString'. Error: ${vf.getMessage}""".stripMargin)
        (true, true)
      case (Failure(kf), _) =>
        logWarning(s"""
               |Failed to pickle Java object as key: ${t._1.getClass.getSimpleName}, falling back
               |to 'toString'. Error: ${kf.getMessage}""".stripMargin)
        (true, false)
      case (_, Failure(vf)) =>
        logWarning(s"""
               |Failed to pickle Java object as value: ${t._2.getClass.getSimpleName}, falling back
               |to 'toString'. Error: ${vf.getMessage}""".stripMargin)
        (false, true)
      case _ =>
        (false, false)
    }
  }

  /**
   * Convert an RDD of key-value pairs to an RDD of serialized Python objects, that is usable
   * by PySpark. By default, if serialization fails, toString is called and the string
   * representation is serialized
   */
  def pairRDDToPython(rdd: RDD[(Any, Any)], batchSize: Int): RDD[Array[Byte]] = {
    val (keyFailed, valueFailed) = checkPickle(rdd.first())
    rdd.mapPartitions { iter =>
      val pickle = new Pickler
      val cleaned = iter.map { case (k, v) =>
        val key = if (keyFailed) k.toString else k
        val value = if (valueFailed) v.toString else v
        Array[Any](key, value)
      }
      if (batchSize > 1) {
        cleaned.grouped(batchSize).map(batched => pickle.dumps(seqAsJavaList(batched)))
      } else {
        cleaned.map(pickle.dumps(_))
      }
    }
  }

  /**
   * Convert an RDD of serialized Python tuple (K, V) to RDD[(K, V)].
   */
  def pythonToPairRDD[K, V](pyRDD: RDD[Array[Byte]], batchSerialized: Boolean): RDD[(K, V)] = {
    def isPair(obj: Any): Boolean = {
      Option(obj.getClass.getComponentType).map(!_.isPrimitive).getOrElse(false) &&
        obj.asInstanceOf[Array[_]].length == 2
    }
    pyRDD.mapPartitions { iter =>
      val unpickle = new Unpickler
      val unpickled =
        if (batchSerialized) {
          iter.flatMap { batch =>
            unpickle.loads(batch) match {
              case objs: java.util.List[_] => collectionAsScalaIterable(objs)
              case other => throw new SparkException(
                s"Unexpected type ${other.getClass.getName} for batch serialized Python RDD")
            }
          }
        } else {
          iter.map(unpickle.loads(_))
        }
      unpickled.map {
        case obj if isPair(obj) =>
          // we only accept (K, V)
          val arr = obj.asInstanceOf[Array[_]]
          (arr.head.asInstanceOf[K], arr.last.asInstanceOf[V])
        case other => throw new SparkException(
          s"RDD element of type ${other.getClass.getName} cannot be used")
      }
    }
  }

}

