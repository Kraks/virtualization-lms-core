package scala.virtualization.lms
package epfl
package test11

import common._
import test1._
import test7._
import test8.{ArrayMutation,ArrayMutationExp,ScalaGenArrayMutation,OrderingOpsExpOpt}

import util.OverloadHack
import scala.reflect.SourceContext

import java.io.{PrintWriter,StringWriter,FileOutputStream}
import scala.reflect.SourceContext


trait DSL extends LiftNumeric with NumericOps with ArrayOps with RangeOps with BooleanOps 
  with LiftVariables with IfThenElse with While with Print {
  def staticData[T:Manifest](x: T): Rep[T]
  def test(x: Rep[Int]): Rep[Any]
}
trait Impl extends DSL with ArrayOpsExpOpt with NumericOpsExpOpt with OrderingOpsExpOpt with BooleanOpsExp 
    with EqualExpOpt with VariablesExpOpt with RangeOpsExp with StaticDataExp
    with IfThenElseExpOpt with WhileExpOptSpeculative with SplitEffectsExpFat with PrintExp 
    with CompileScala { self => 
  override val verbosity = 1
  val codegen = new ScalaGenNumericOps with ScalaGenStaticData with ScalaGenOrderingOps with ScalaGenArrayOps with ScalaGenRangeOps
    with ScalaGenVariables with ScalaGenIfThenElseFat with ScalaGenWhileOptSpeculative with ScalaGenSplitEffects
    with ScalaGenPrint /*with LivenessOpt*/ { val IR: self.type = self }
  codegen.emitSource(test, "Test", new PrintWriter(System.out))
  val f = compile(test)
  f(7)
}


class TestHMM extends FileDiffSuite {
  
  val prefix = "test-out/epfl/test11-"
  
  def testHmm1 = {
    withOutFileChecked(prefix+"hmm1") {
      trait Prog extends DSL with ArrayOps with NumericOps {
        def test(x: Rep[Int]) = {

          val A = scala.Array
          
          val a = A(A(1,0,0,1,0),
                    A(0,0,1,0,0),
                    A(0,1,0,0,0),
                    A(0,0,1,1,1),
                    A(0,0,1,0,1))

          val n = 5

          def sparse_mv_prod(a: Array[Array[Int]], v: Rep[Array[Int]]) = {
            val v1 = NewArray[Int](n)
            for (i <- 0 until n: Range) {
              if ((a(i) filter (_ != 0)).length < 3) {
                for (j <- 0 until n: Range) {
                  if (a(i)(j) != 0)
                    v1(i) = v1(i) + a(i)(j) * v(j)
                }
              } else {
                for (j <- 0 until n: Rep[Range]) {
                  v1(i) = v1(i) + (staticData(a(i)) apply j) * v(j)
                }
              }
            }
            v1
          }

          val v = Array(3, 1, 5, -2, 4)
          
          val v1 = sparse_mv_prod(a,v)
          
          v1 foreach { print(_) }
        }
      }
      new Prog with Impl with ArrayOpsExpOpt with NumericOpsExpOpt
    }
  }

  def testHmm2 = {
    withOutFileChecked(prefix+"hmm2") {
      trait Prog extends DSL with ArrayOps with NumericOps {
        def test(x: Rep[Int]) = {

          def unrollIf(range: Range)(cond: Boolean) = new {
            def foreach(f: Rep[Int] => Unit): Unit = {
              if (cond) for (i <- range) f(i)
              else for (i <- (range.start until range.end): Rep[Range]) f(i)
            }
          }

          val A = scala.Array
          
          val a = A(A(1,0,0,1,0),
                    A(0,0,1,0,0),
                    A(0,1,0,0,0),
                    A(0,0,1,1,1),
                    A(0,0,1,0,1))

          val n = 5

          def sparse_mv_prod(a: Array[Array[Int]], v: Rep[Array[Int]]) = {
            val v1 = NewArray[Int](n)
            for (i <- 0 until n: Range) {
              for (j <- unrollIf(0 until n)((a(i) filter (_ != 0)).length < 3)) {
                v1(i) = v1(i) + (staticData(a(i)) apply j) * v(j)
              }
            }
            v1
          }

          val v = Array(3, 1, 5, -2, 4)
          
          val v1 = sparse_mv_prod(a,v)
          
          v1 foreach { print(_) }
        }
      }
      new Prog with Impl with ArrayOpsExpOpt with NumericOpsExpOpt {
        override def array_apply[T:Manifest](x: Exp[Array[T]], n: Exp[Int])(implicit pos: SourceContext): Exp[T] = (x,n) match {
          case (Def(StaticData(x:Array[T])), Const(n)) => Const(x(n))
          case _ => super.array_apply(x,n)
        }
        
      }
    }
  }

 
}