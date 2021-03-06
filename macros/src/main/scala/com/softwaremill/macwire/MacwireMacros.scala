package com.softwaremill.macwire

import com.softwaremill.macwire.dependencyLookup._

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait Macwire {
  def wire[T]: T = macro MacwireMacros.wire_impl[T]
  def wireImplicit[T]: T = macro MacwireMacros.wireImplicit_impl[T]
  def wiredInModule(in: AnyRef): Wired = macro MacwireMacros.wiredInModule_impl
}

object MacwireMacros extends Macwire {
  private val debug = new Debug()

  def wire_impl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[T] = doWire(c, wireWithImplicits = false)

  def wireImplicit_impl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[T] = doWire(c, wireWithImplicits = true)

  private def doWire[T: c.WeakTypeTag](c: blackbox.Context, wireWithImplicits: Boolean): c.Expr[T] = {
    import c.universe._

    lazy val dependencyResolver = new DependencyResolver[c.type](c, debug, wireWithImplicits)

    def createNewTargetWithParams(): Expr[T] = {
      val targetType = implicitly[c.WeakTypeTag[T]]
      debug.withBlock(s"Trying to find parameters to create new instance of: [${targetType.tpe}]") {
        val targetConstructorOpt = targetType.tpe.members.find(m => m.isMethod && m.asMethod.isPrimaryConstructor)
        targetConstructorOpt match {
          case None =>
            c.abort(c.enclosingPosition, "Cannot find constructor for " + targetType)
          case Some(targetConstructor) =>
            val targetConstructorParamLists = targetConstructor.asMethod.paramLists
            // We need to get the "real" type in case the type parameter is a type alias - then it cannot
            // be directly instatiated
            val targetTpe = targetType.tpe.dealias

            val (sym, tpeArgs) = targetTpe match {
              case TypeRef(_, sym, tpeArgs) => (sym, tpeArgs)
              case t => c.abort(c.enclosingPosition, s"Target type not supported for wiring: $t. Please file a bug report with your use-case.")
            }

            var newT: Tree = Select(New(Ident(targetTpe.typeSymbol)), termNames.CONSTRUCTOR)

            for {
              targetConstructorParams <- targetConstructorParamLists
            } {
              val constructorParams: List[c.Tree] = for (param <- targetConstructorParams) yield {
                // Resolve type parameters
                val pTpe = param.typeSignature.substituteTypes(sym.asClass.typeParams, tpeArgs)

                val pEffectiveTpe = if (param.asTerm.isByNameParam) {
                  pTpe.typeArgs.head
                } else {
                  pTpe
                }

                val wireToOpt = dependencyResolver.resolve(param, pEffectiveTpe)

                // If no value is found, an error has been already reported.
                wireToOpt.getOrElse(reify(null).tree)
              }

              newT = Apply(newT, constructorParams)
            }

            debug(s"Generated code: ${c.universe.show(newT)}")
            c.Expr(newT)
        }
      }
    }

    createNewTargetWithParams()
  }

  def wiredInModule_impl(c: blackbox.Context)(in: c.Expr[AnyRef]): c.Expr[Wired] = {
    import c.universe._

    // Ident(scala.Predef)
    val Expr(predefIdent) = reify { Predef }
    val Expr(wiredIdent) = reify { Wired }

    def extractTypeFromNullaryType(tpe: Type) = {
      tpe match {
        case NullaryMethodType(underlying) => Some(underlying)
        case _ => None
      }
    }

    val capturedInName = c.freshName()

    def instanceFactoriesByClassInTree(tree: Tree): List[Tree] = {
      val members = tree.tpe.members

      val pairs = members
        .filter(_.isMethod)
        .flatMap { m =>
        extractTypeFromNullaryType(m.typeSignature) match {
          case Some(tpe) => Some((m, tpe))
          case None =>
            debug(s"Cannot extract type from ${m.typeSignature} for member $m!")
            None
        }
      }.map { case (member, tpe) =>
        val key = Literal(Constant(tpe))
        val value = Select(Ident(TermName(capturedInName)), TermName(member.name.decodedName.toString.trim))

        debug(s"Found a mapping: $key -> $value")

        // Generating: () => value
        val valueExpr = c.Expr[AnyRef](value)
        val createValueExpr = reify { () => valueExpr.splice }

        // Generating: key -> value
        Apply(Select(Apply(Select(predefIdent, TermName("ArrowAssoc")), List(key)),
          TermName("$minus$greater")), List(createValueExpr.tree))
      }

      pairs.toList
    }

    debug.withBlock(s"Generating wired-in-module for ${in.tree}") {
      val pairs = instanceFactoriesByClassInTree(in.tree)

      // Generating:
      // {
      //   val inName = in
      //   Wired(Map(...))
      // }
      val captureInTree = ValDef(Modifiers(), TermName(capturedInName), TypeTree(), in.tree)
      val newWiredTree = Apply(Select(wiredIdent, TermName("apply")), List(
        Apply(Select(Select(predefIdent, TermName("Map")), TermName("apply")), pairs)))
      c.Expr[Wired](Block(captureInTree, newWiredTree))
    }
  }
}
