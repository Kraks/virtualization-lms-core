package scala.lms
package internal

import util.GraphUtil
import java.io.{File, PrintWriter}


trait CodeMotion extends Scheduling {
  val IR: Expressions with Effects /* effects just for sanity check */
  import IR._

  def getExactScope[A](currentScope: List[Stm])(result: List[Exp[Any]]): List[Stm] = {
    // currentScope must be tight for result and strongly sorted
    val e1 = currentScope.toSet

    // shallow is 'must outside + should outside' <--- currently shallow == deep for lambdas, meaning everything 'should outside'
    // bound is 'must inside'

    // find transitive dependencies on bound syms, including their defs (in case of effects)
    val bound = e1.flatMap(z => boundSyms(z.rhs))
    val g1 = getFatDependentStuff(currentScope)(bound.toList).toSet

    // e1 = reachable
    // val h1 = e1 filterNot (g1 contains _) // 'may outside'
    val h1 = (e1 diff g1)

    // return h1.toList

    val h1Inv = new scala.collection.mutable.HashMap[Sym[Any], List[Stm]] { override def default(k:Sym[Any]) = Nil }
    h1 foreach { stm => stm.lhs.foreach { s => h1Inv(s) = stm::h1Inv(s) }}

    // val f1 = g1.flatMap { t => syms(t.rhs) } flatMap { s => h1 filter (_.lhs contains s) } // fringe: 1 step from g1
    val f1 = g1.flatMap { t => syms(t.rhs) flatMap h1Inv }

    val e2 = getScheduleM(currentScope)(result, false, true).toSet       // (shallow|hot)*  no cold ref on path

    val e3 = getScheduleM(currentScope)(result, true, false).toSet       // (shallow|cold)* no hot ref on path

    // val f2 = f1 filterNot (e3 contains _)                   // fringe restricted to: any* hot any*
    val f2 = f1 diff e3

    val h2 = getScheduleM(currentScope)(f2.flatMap(_.lhs), false, true)    // anything that depends non-cold on it...

    // things that should live on this level:
    // - not within conditional: no cold ref on path (shallow|hot)*
    // - on the fringe but outside of mustInside, if on a hot path any* hot any*

    // TODO: use (shallow|hot)* hot any* instead

    // ---- begin FIXME ----

    // AKS: temporarily reverted to old code here to patch a bug in OptiML's MiniMSMBuilder application
    // then/else branches were being unsafely hoisted out of a conditional
    //val shouldOutside = e1 filter (z => (e2 contains z) || (h2 contains z))

    //*
    //TODO: uncomment after resolving the issue above

    // val loopsNotInIfs = e2 filterNot (e3 contains _)    // (shallow|hot)* hot (shallow|hot)*   <---- a hot ref on all paths!
    val loopsNotInIfs = e2 diff e3
    val reachFromTopLoops = getSchedule(currentScope)(loopsNotInIfs,false).toSet

    // val f3 = f1 filter (reachFromTopLoops contains _)    // fringe restricted to: (shallow|hot)* hot any*
    val f3 = f1 intersect reachFromTopLoops
    val h3 = getScheduleM(currentScope)(f3.flatMap(_.lhs), false, true).toSet    // anything that depends non-cold on it...

    // val shouldOutside = e1 filter (z => (e2 contains z) || (h3 contains z))
    val shouldOutside = e1 intersect (e2 union h3)

    //val shouldOutside = e1 filter (z => (e2 contains z) || (h2 contains z))
    //*/

    val levelScope = currentScope.filter(z => (shouldOutside contains z) && !(g1 contains z)) // shallow (but with the ordering of deep!!) and minus bound

    // ---- end FIXME ----

    // sym->sym->hot->sym->cold->sym  hot --> hoist **** iff the cold is actually inside the loop ****
    // sym->sym->cold->sym->hot->sym  cold here, hot later --> push down, then hoist

/*

     loop { i =>                z = *if (x) bla
       if (i > 0)               loop { i =>
         *if (x)                  if (i > 0)
           bla                      z
     }                          }

     loop { i =>                z = *bla
       if (x)                   loop { i =>
         if (i > 0)               if (x)
           *bla                     if (i > 0)
     }                                z
                                }
*/


    // // TODO: recursion!!!  identify recursive up-pointers







    object LocalDef {
      def unapply[A](x: Exp[A]): Option[Stm] = { // fusion may have rewritten Reify contents so we look at local scope
        currentScope.find(_.lhs contains x)
      }
    }

    // sanity check to make sure all effects are accounted for
    // result foreach {
    //   case LocalDef(TP(_, Reify(x, u, effects))) =>
    //     val acteffects = levelScope.flatMap(_.lhs) filter (effects contains _)
    //     if (effects.toSet != acteffects.toSet) {
    //       val actual = levelScope.filter(_.lhs exists (effects contains _))
    //       val expected = effects.map(d=>/*fatten*/(findDefinition(d.asInstanceOf[Sym[Any]]).get))
    //       val missing = expected filterNot (actual contains _)
    //       val printfn = if (missing.isEmpty) printlog _ else printerr _
    //       printfn("error: violated ordering of effects")
    //       printfn("  expected:")
    //       expected.foreach(d => printfn("    "+d))
    //       printfn("  actual:")
    //       actual.foreach(d => printfn("    "+d))
    //       // stuff going missing because of stray dependencies is the most likely cause
    //       // so let's print some debugging hints
    //       printfn("  missing:")
    //       if (missing.isEmpty)
    //         printfn("  note: there is nothing missing so the different order might in fact be ok (artifact of new effect handling? TODO)")
    //       missing.foreach { d =>
    //         val inDeep = e1 contains d
    //         val inShallow = e2 contains d
    //         val inDep = g1 contains d
    //         printfn("    "+d+" <-- inDeep: "+inDeep+", inShallow: "+inShallow+", inDep: "+inDep)
    //         if (inDep) e1 foreach { z =>
    //           val b = boundSyms(z.rhs)
    //           if (b.isEmpty) "" else {
    //             val g2 = getFatDependentStuff(currentScope)(b)
    //             if (g2 contains d) {
    //               printfn("    depends on " + z + " (bound: "+b+")")
    //               val path = getSchedule(g2.toList)(d)
    //               for (p <- path) printfn("      "+p)
    //             }
    //           }
    //         }
    //       }
    //     }
    //   case _ =>
    // }
/*
    // sanity check to make sure all effects are accounted for
    result match {
      case Def(Reify(x, u, effects)) =>
        val actual = levelScope.filter(effects contains _.sym)
        assert(effects == actual.map(_.sym), "violated ordering of effects: expected \n    "+effects+"\nbut got\n    " + actual)
      case _ =>
    }
*/


    levelScope
  }
}
