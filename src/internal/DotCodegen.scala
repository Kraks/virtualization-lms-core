package scala.virtualization.lms
package internal

import java.io.{File, FileWriter, PrintWriter}

import scala.reflect.SourceContext

trait DotCodegen extends GenericCodegen with Config {
  val IR: Expressions
  import IR._

	var inHwScope = false

  override def deviceTarget: Targets.Value = Targets.Dot

  override def fileExtension = "dot"

  override def toString = "dot"

  override def resourceInfoType = ""
  override def resourceInfoSym = ""

  // Generate all code into one file
  override def emitSingleFile() = true

  override def emitSource[A : Manifest](args: List[Sym[_]], body: Block[A], className: String, out: PrintWriter) = {

    val sA = remap(manifest[A])

    val staticData = getFreeDataBlock(body)

    withStream(out) {
      stream.println("/*****************************************\n"+
                     "  DOT BACKEND: emitSource \n"+
                     "*******************************************/")
      emitFileHeader()

      stream.println("digraph G {")
      try {
        emitBlock(body)
      } catch {
        case e: GenerationFailedException =>
          stream.println("// Generation failed exception")
          Console.println(Console.BLACK + Console.YELLOW_B + e.getMessage() + Console.RESET)
      }
      stream.println("}")

      stream.println("/*****************************************\n"+
                     "  End of DOT BACKEND \n"+
                     "*******************************************/")
    }

    staticData
  }

  private var bd = ""

  override def initializeGenerator(buildDir: String) = {
    bd = buildDir
    val sep = java.io.File.separator
    val outDir = new File(buildDir); outDir.mkdirs()
  }

  override def finalizeGenerator() = {
  }

  override def emitFileHeader() {
    // empty by default. override to emit package or import declarations.
  }

  override def emitKernelHeader(syms: List[Sym[Any]], vals: List[Sym[Any]], vars: List[Sym[Any]], resultType: String, resultIsVar: Boolean, external: Boolean, isMultiLoop: Boolean): Unit = {
    val kernelName = syms.map(quote).mkString("")
    stream.print("// kernel_" + kernelName + " (")
    if (resourceInfoType != "") {
      stream.print(resourceInfoSym + ":" + resourceInfoType)
      if ((vals ++ vars).length > 0) stream.print(",")
    }
    // Print all val arguments
    stream.print(vals.map(p => quote(p) + ":" + remap(p.tp)).mkString(","))

    // Print all var arguments
    if (vals.length > 0 && vars.length > 0){
      stream.print(",")
    }
    // TODO: remap Ref instead of explicitly adding generated.scala
    if (vars.length > 0){
      stream.print(vars.map(v => quote(v) + ":" + "generated.scala.Ref[" + remap(v.tp) +"]").mkString(","))
    }

    // Print result type
    if (resultIsVar){
      stream.print("): " + "generated.scala.Ref[" + resultType + "] = {")
    }
    else {
      stream.print("): " + resultType + " = {")
    }

    stream.println("")
  }

  override def emitKernelFooter(syms: List[Sym[Any]], vals: List[Sym[Any]], vars: List[Sym[Any]], resultType: String, resultIsVar: Boolean, external: Boolean, isMultiLoop: Boolean): Unit = {
    val kernelName = syms.map(quote).mkString("")
    stream.println(s"// } $kernelName")
  }

  def relativePath(fileName: String): String = {
    val i = fileName.lastIndexOf('/')
    fileName.substring(i + 1)
  }

  override def emitValDef(sym: Sym[Any], rhs: String): Unit = {
    val extra = if ((sourceinfo < 2) || sym.pos.isEmpty) "" else {
      val context = sym.pos(0)
      "      // " + relativePath(context.fileName) + ":" + context.line
    }
		if (!isVoidType(sym.tp))
    	stream.println("val " + quote(sym) + " = " + rhs + extra)
  }

  def emitVarDef(sym: Sym[Variable[Any]], rhs: String): Unit = {
    stream.println("var " + quote(sym) + ": " + remap(sym.tp) + " = " + rhs)
  }

  override def emitVarDecl(sym: Sym[Any]): Unit = {
    stream.println("var " + quote(sym) + ": " + remap(sym.tp) + " = null.asInstanceOf[" + remap(sym.tp) + "];")
  }

  override def emitAssignment(sym: Sym[Any], rhs: String): Unit = {
    stream.println(quote(sym) + " = " + rhs)
  }

  override def quote(x: Exp[Any]) = x match {
    case Const(l: Long) => l.toString + "L"
    case Const(null) => "null.asInstanceOf["+x.tp+"]"
    case _ => super.quote(x)
  }

	def emitAlias(x: Exp[Any], y: Exp[Any]) {
		stream.println(s"""define(`${quote(x)}', `${quote(y)}')""")
	}
	def emitAlias(x: Sym[Any], y: String) {
		stream.println(s"""define(`${quote(x)}', `${y}')""")
	}

	def emit(str: String):Unit = {
		stream.println(str)
	}

	def emitComment(str: String):Unit = {
		stream.println(s"""/* $str */ """)
	}

	val arrowSize = 0.6
	val edgeThickness = 0.5
	val memColor = "#6ce6e1"
	val regColor = "#8bd645"
	val offChipColor = "#1A0000"
	val dblbufBorderColor = "#4fb0b0"
	val ctrlColor = "red"
	val counterColor = "#e8e8e8"
	val counterInnerColor = "gray"
	val fontsize = 10
	val defaultShape = "square"
	val bgcolor = "white"

	// Metapipeline colors
	val mpFillColor = "#4FA1DB"
	val mpBorderColor = "#4FA1DB"
	val mpStageFillColor = "#BADDFF"
	val mpStageBorderColor = "none"

	// Parallel colors
	val parallelFillColor = "#4FDBC2"
	val parallelBorderColor = "#00AB8C"
	val parallelStageFillColor = "#CCFFF6"
	val parallelStageBorderColor = "none"

}

trait DotNestedCodegen extends GenericNestedCodegen with DotCodegen {
  val IR: Expressions with Effects
  import IR._

  // emit forward decls for recursive vals
  override def traverseStmsInBlock[A](stms: List[Stm]): Unit = {
    recursive foreach emitForwardDef
    super.traverseStmsInBlock(stms)
  }

  def emitForwardDef(sym: Sym[Any]): Unit = {
    stream.println("var " + quote(sym) + /*": " + remap(sym.tp) +*/ " = null.asInstanceOf[" + remap(sym.tp) + "]")
  }

  // special case for recursive vals
  override def emitValDef(sym: Sym[Any], rhs: String): Unit = {
    if (recursive contains sym)
      stream.println(quote(sym) + " = " + rhs) // we have a forward declaration above.
    else
      super.emitValDef(sym,rhs)
  }

}


trait DotFatCodegen extends GenericFatCodegen with DotCodegen {
  val IR: Expressions with Effects with FatExpressions
  import IR._
}