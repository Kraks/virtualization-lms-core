package scala.virtualization.lms
package epfl
package test7

import common._
import test1._

import util.OverloadHack

import java.io.{PrintWriter,StringWriter,FileOutputStream}



trait ArrayLoops extends Loops with OverloadHack {
  def array[T:Manifest](shape: Rep[Int])(f: Rep[Int] => Rep[T]): Rep[Array[T]]
  def sum(shape: Rep[Int])(f: Rep[Int] => Rep[Double]): Rep[Double] // TODO: make reduce operation configurable!
  def arrayIf[T:Manifest](shape: Rep[Int])(f: Rep[Int] => (Rep[Boolean],Rep[T])): Rep[Array[T]]
  def sumIf(shape: Rep[Int])(f: Rep[Int] => (Rep[Boolean],Rep[Double])): Rep[Double] // TODO: make reduce operation configurable!
  def flatten[T:Manifest](shape: Rep[Int])(f: Rep[Int] => Rep[Array[T]]): Rep[Array[T]]

  def infix_at[T:Manifest](a: Rep[Array[T]], i: Rep[Int]): Rep[T]
  def infix_length[T:Manifest](a: Rep[Array[T]]): Rep[Int]
}


trait ArrayLoopsExp extends LoopsExp {
  
  trait Accu[T]
  trait Gen[T]
  
  case class ArrayElem[T](g: Exp[Gen[T]], y: Exp[Gen[T]]) extends Def[Array[T]]
  case class ReduceElem(g: Exp[Gen[Double]], y: Exp[Gen[Double]]) extends Def[Double]

  case class ArrayIfElem[T](g: Exp[Gen[T]], c: Exp[Boolean], y: Exp[Gen[T]]) extends Def[Array[T]]
  case class ReduceIfElem(g: Exp[Gen[Double]], c: Exp[Boolean], y: Exp[Gen[Double]]) extends Def[Double]

  case class FlattenElem[T](g: Exp[Gen[Array[T]]], y: Exp[Gen[Array[T]]]) extends Def[Array[T]]

  case class ArrayIndex[T](a: Rep[Array[T]], i: Rep[Int]) extends Def[T]  
  case class ArrayLength[T](a: Rep[Array[T]]) extends Def[Int]


  case class Yield[T](g: Exp[Int], a: Exp[T]) extends Def[Gen[T]]
  case class Skip[T](g: Exp[Int]) extends Def[Gen[T]]

  
  def array[T:Manifest](shape: Rep[Int])(f: Rep[Int] => Rep[T]): Rep[Array[T]] = {
    //val g = fresh[Accu[T]]
    val x = fresh[Int]
    var g: Exp[Gen[T]] = null
    val y = reifyEffects { 
      g = Yield(x,f(x))
      g
    }
    reflectEffect(SimpleLoop(shape, x, ArrayElem(g,y)), summarizeEffects(y).star)
  }

  def sum(shape: Rep[Int])(f: Rep[Int] => Rep[Double]): Rep[Double] = {
    //val g = fresh[Accu[Double]]
    val x = fresh[Int]
    var g: Exp[Gen[Double]] = null
    val y = reifyEffects { 
      g = Yield(x,f(x))
      g
    }
    reflectEffect(SimpleLoop(shape, x, ReduceElem(g,y)), summarizeEffects(y).star)
  }

  def arrayIf[T:Manifest](shape: Rep[Int])(f: Rep[Int] => (Rep[Boolean],Rep[T])): Rep[Array[T]] = {
    val x = fresh[Int]
    val (c,y) = f(x) //FIXME
    val g = toAtom(Yield(x,y))
    SimpleLoop(shape, x, ArrayIfElem(g,c,g)) // TODO: simplify for const true/false
  }

  def sumIf(shape: Rep[Int])(f: Rep[Int] => (Rep[Boolean],Rep[Double])): Rep[Double] = {
    val x = fresh[Int]
    val (c,y) = f(x) //FIXME
    val g = toAtom(Yield(x,y))
    SimpleLoop(shape, x, ReduceIfElem(g,c,g)) // TODO: simplify for const true/false
  }

  def flatten[T:Manifest](shape: Rep[Int])(f: Rep[Int] => Rep[Array[T]]): Rep[Array[T]] = {
    val x = fresh[Int]
    val y = f(x) //FIXME
    val g = toAtom(Yield(x,y))
    SimpleLoop(shape, x, FlattenElem(g,g))
  }


  def infix_at[T:Manifest](a: Rep[Array[T]], i: Rep[Int]): Rep[T] = ArrayIndex(a, i)

  def infix_length[T:Manifest](a: Rep[Array[T]]): Rep[Int] = a match {
//    case Def(SimpleLoop(s, x, ArrayElem(g1,Def(Yield(x2,y))))) if x == x2 => s // TODO: check condition
    case Def(SimpleLoop(s, x, ArrayElem(g,y))) if g == y => s // TODO: check condition
    case _ => ArrayLength(a)
  }


  override def syms(e: Any): List[Sym[Any]] = e match {
    case ArrayElem(g,y) => syms(y)
    case ReduceElem(g,y) => syms(y)
    case FlattenElem(g,y) => syms(y)
    case _ => super.syms(e)
  }

  override def symsFreq(e: Any) = e match {
    case ArrayElem(g,y) => freqNormal(y)
    case ReduceElem(g,y) => freqNormal(y)
    case FlattenElem(g,y) => freqNormal(y)
    case _ => super.symsFreq(e)
  }
  
  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case ArrayElem(g,y) => effectSyms(y)
    case ReduceElem(g,y) => effectSyms(y)
    case FlattenElem(g,y) => effectSyms(y)
    case _ => super.boundSyms(e)
  }

}

trait ArrayLoopsFatExp extends ArrayLoopsExp with LoopsFatExp




trait ScalaGenArrayLoops extends ScalaGenLoops {
  val IR: ArrayLoopsExp
  import IR._
  
  // TODO: multiple gens
  var genStack: Map[Exp[Gen[_]], String=>Unit] = Map.empty
  def withGens[A](p: List[(Exp[Gen[_]], String=>Unit)])(body: =>A):A = {
    val save = genStack
    genStack = genStack ++ p
    println("--- withGens " + p + " == " + genStack)
    val res = body
    genStack = save
    res
  }
  
  def withGen[T,A](g: Exp[Gen[T]], f: String=>Unit)(body: =>A):A = withGens(List((g,f)))(body)
  def topGen[T](g: Exp[Gen[T]]): String => Unit = {
    genStack.getOrElse(g, (s => "UNKNOWN: "+s))
  }
  
  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    case SimpleLoop(s,x,ArrayElem(g,y)) =>  
      stream.println("val " + quote(sym) + " = LoopArray("+quote(s)+") { " + quote(x) + " => ")
      withGen(g, s=>stream.println(s)) {
        emitBlock(y)
      }
      stream.println("}")
    case SimpleLoop(s,x,ReduceElem(g,y)) =>  
      stream.println("val " + quote(sym) + " = LoopReduce("+quote(s)+") { " + quote(x) + " => ")
      withGen(g, s=>stream.println(s)) {
        emitBlock(y)
      }
      stream.println("}")
    // TODO: conditional variants ...
    case SimpleLoop(s,x,FlattenElem(g,y)) =>  
      stream.println("val " + quote(sym) + " = LoopFlatten("+quote(s)+") { " + quote(x) + " => ")
      withGen(g, s=>stream.println(s)) {
        emitBlock(y)
      }
      stream.println("}")
    case ArrayIndex(a,i) =>  
      emitValDef(sym, quote(a) + ".apply(" + quote(i) + ")")
    case ArrayLength(a) =>  
      emitValDef(sym, quote(a) + ".length")
    case Yield(g,a) => 
      if (genStack.nonEmpty)topGen(sym.asInstanceOf[Sym[Gen[Any]]])(quote(a))
      else emitValDef(sym, "yield " + quote(a) + " // context is messed up!")
    case Skip(g) => 
    case _ => super.emitNode(sym, rhs)
  }
}

trait ScalaGenArrayLoopsFat extends ScalaGenArrayLoops with ScalaGenLoopsFat {
  val IR: ArrayLoopsFatExp
  import IR._
  
  override def emitFatNode(sym: List[Sym[Any]], rhs: FatDef)(implicit stream: PrintWriter) = rhs match {
    case SimpleFatLoop(s,x,rhs) => 
      for ((l,r) <- sym zip rhs) {
        r match {
          case ArrayElem(g,y) if g == y => 
            stream.println("val " + quote(l) + " = new Array[]("+quote(s)+")")
          case ArrayElem(g,y) =>
            stream.println("val " + quote(g) + " = new ArrayBuilder[]")
          case ReduceElem(g,y) =>
            stream.println("var " + quote(l) + " = 0")
          case ArrayIfElem(g,c,y) =>
            stream.println("val " + quote(g) + " = new ArrayBuilder[]")
          case ReduceIfElem(g,c,y) =>
            stream.println("var " + quote(l) + " = 0")
          case FlattenElem(g,y) =>
            stream.println("val " + quote(g) + " = new ArrayBuilder[]")
        }
      }
      val ii = x
      stream.println("for ("+quote(ii)+" <- 0 until "+quote(s)+") {")

      val gens = for ((l,r) <- sym zip rhs) yield r match {
        case ArrayElem(g,y) if g == y => 
          (g, (s: String) => stream.println(quote(l) + "("+quote(ii)+") = " + s))
        case ArrayElem(g,y) =>
          (g, (s: String) => stream.println(quote(g) + " += " + s))
        case ReduceElem(g,y) =>
          (g, (s: String) => stream.println(quote(l) + " += " + s))
        case ArrayIfElem(g,c,y) =>
          (g, (s: String) => stream.println("if ("+quote(getBlockResult(c))+") " + quote(g) + " += " + s))
        case ReduceIfElem(g,c,y) =>
          (g, (s: String) => stream.println("if ("+quote(getBlockResult(c))+") " + quote(l) + " += " + s))
        case FlattenElem(g,y) =>
          (g, (s: String) => stream.println(quote(g) + " ++= " + s))
      }

      withGens(gens) {
        emitFatBlock(syms(rhs))
      }

      stream.println("}")

      for ((l,r) <- sym zip rhs) r match {
        case ArrayElem(g,y) if g == y => 
        case ArrayElem(g,y) =>
          stream.println("val " + quote(l) + " = " + quote(g) + ".result")
        case ReduceElem(g,y) =>
        case ArrayIfElem(g,c,y) =>
          stream.println("val " + quote(l) + " = " + quote(g) + ".result")
        case ReduceIfElem(g,c,y) =>
        case FlattenElem(g,y) =>
          stream.println("val " + quote(l) + " = " + quote(g) + ".result")
      }

    case _ => super.emitFatNode(sym, rhs)
  }
}








trait Arrays extends Base with OverloadHack {
  def zeroes(n: Rep[Int]): Rep[Array[Int]]
  def infix_update(a: Rep[Array[Int]], x: Rep[Int], v: Rep[Int]): Rep[Array[Int]]
  def infix_+(a: Rep[Array[Int]], b: Rep[Array[Int]])(implicit o: Overloaded1): Rep[Array[Int]]
}

trait ArraysExp extends Arrays with EffectExp {
  case class ArrayZero(n: Rep[Int]) extends Def[Array[Int]]
  case class ArrayUpdate(a: Rep[Array[Int]], x: Rep[Int], v: Rep[Int]) extends Def[Array[Int]]
  case class ArrayPlus(a: Rep[Array[Int]], b: Rep[Array[Int]]) extends Def[Array[Int]]
  def zeroes(n: Rep[Int]) = ArrayZero(n)
  def infix_update(a: Rep[Array[Int]], x: Rep[Int], v: Rep[Int]) = ArrayUpdate(a,x,v)
  def infix_+(a: Rep[Array[Int]], b: Rep[Array[Int]])(implicit o: Overloaded1) = ArrayPlus(a,b)
}

trait ScalaGenArrays extends ScalaGenEffect {
  val IR: ArraysExp
  import IR._
  
  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    case ArrayZero(n) =>  
      emitValDef(sym, "new Array[Int](" + quote(n) + ")")
    case ArrayUpdate(a,x,v) =>  
      emitValDef(sym, quote(a) +".clone()")
      stream.println(quote(sym) + "(" + quote(x) + ") = " + quote(v))
    case ArrayPlus(a,b) =>  
      emitValDef(sym, "new Array[Int](" + quote(a) + ".length)")
      stream.println("arrayPlus("+ quote(sym) + "," + quote(a) + "," + quote(b) + ")")
    case _ => super.emitNode(sym, rhs)
  }
}

