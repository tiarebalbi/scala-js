/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.core.compiler

import scala.collection.mutable

import scala.reflect.internal.Flags.{LOCAL, PRIVATE}
import scala.tools.nsc._

/** Hacks to have our source code compatible with 2.10 and 2.11.
 *  It exposes 2.11 API in a 2.10 compiler.
 *
 *  @author Sébastien Doeraene
 */
trait Compat210Component {
  import Compat210Component.{infiniteLoop, noImplClasses}

  val global: Global

  import global._

  // unexpandedName replaces originalName

  implicit final class SymbolCompat(self: Symbol) {
    def unexpandedName: Name = self.originalName
    def originalName: Name = infiniteLoop()

    def isPrivateThis: Boolean = self.hasAllFlags(PRIVATE | LOCAL)
    def isLocalToBlock: Boolean = self.isLocal

    def originalOwner: Symbol =
      global.originalOwner.getOrElse(self, self.rawowner)

    def implClass: Symbol = NoSymbol

    def isTraitOrInterface: Boolean = self.isTrait || self.isInterface
  }

  // Global compat

  @inline final def enteringPhase[T](ph: Phase)(op: => T): T = {
    global.enteringPhase(ph)(op)
  }

  @inline final def exitingPhase[T](ph: Phase)(op: => T): T = {
    global.exitingPhase(ph)(op)
  }

  @inline final def devWarning(msg: => String): Unit =
    global.devWarning(msg)

  @inline final def forScaladoc: Boolean =
    global.forScaladoc

  implicit final class GlobalCompat(
      self: Compat210Component.this.global.type) {

    object originalOwner {
      def getOrElse(sym: Symbol, orElse: => Symbol): Symbol = infiniteLoop()
    }

    def enteringPhase[T](ph: Phase)(op: => T): T = self.beforePhase(ph)(op)
    def beforePhase[T](ph: Phase)(op: => T): T = infiniteLoop()

    def exitingPhase[T](ph: Phase)(op: => T): T = self.afterPhase(ph)(op)
    def afterPhase[T](ph: Phase)(op: => T): T = infiniteLoop()

    def delambdafy: DelambdafyCompat.type = DelambdafyCompat

    def devWarning(msg: => String): Unit = self.debugwarn(msg)
    def debugwarn(msg: => String): Unit = infiniteLoop()

    def forScaladoc: Boolean =
      self.isInstanceOf[ScaladocGlobalCompat.ScaladocGlobalCompat]
  }

  object ScaladocGlobalCompat {
    object Compat {
      trait ScaladocGlobal
    }

    import Compat._

    object Inner {
      import scala.tools.nsc.doc._

      type ScaladocGlobalCompat = ScaladocGlobal
    }

    type ScaladocGlobalCompat = Inner.ScaladocGlobalCompat
  }

  object DelambdafyCompat {
    object FreeVarTraverser {
      def freeVarsOf(function: Function): mutable.LinkedHashSet[Symbol] = {
        throw new AssertionError(
            "FreeVarTraverser should not be called on 2.10")
      }
    }
  }

  // Impl classes disappeared in 2.12

  lazy val scalaUsesImplClasses: Boolean =
    definitions.SeqClass.implClass != NoSymbol // a trait we know has an impl class

  def isImplClass(sym: Symbol): Boolean =
    sym.isImplClass

  implicit final class StdTermNamesCompat(self: global.nme.type) {
    def IMPL_CLASS_SUFFIX: String = noImplClasses()

    def isImplClassName(name: Name): Boolean = false
  }

  implicit final class StdTypeNamesCompat(self: global.tpnme.type) {
    def IMPL_CLASS_SUFFIX: String = noImplClasses()

    def interfaceName(implname: Name): TypeName = noImplClasses()
  }

  // SAMFunction was introduced in 2.12 for LMF-capable SAM types

  object SAMFunctionAttachCompatDef {
    /* Should extend PlainAttachment, but it does not exist in 2.10, and we
     * do not actually need this relationship.
     */
    case class SAMFunction(samTp: Type, sam: Symbol, synthCls: Symbol)
  }

  object SAMFunctionAttachCompat {
    import SAMFunctionAttachCompatDef._

    object Inner {
      import global._

      type SAMFunctionAlias = SAMFunction
      val SAMFunctionAlias = SAMFunction
    }
  }

  type SAMFunctionCompat = SAMFunctionAttachCompat.Inner.SAMFunctionAlias
  lazy val SAMFunctionCompat = SAMFunctionAttachCompat.Inner.SAMFunctionAlias

  implicit final class SAMFunctionCompatOps(self: SAMFunctionCompat) {
    // Introduced in 2.12.5 to synthesize bridges in LMF classes
    def synthCls: Symbol = NoSymbol
  }

  /* global.genBCode.bTypes.initializeCoreBTypes()
   *
   * This one has a very particular history:
   * - in 2.10.x, no genBCode in global
   * - in 2.11.{0-1}, there is genBCode but it has no bTypes member
   * - In 2.11.{2-5}, there is genBCode.bTypes, but it has no
   *   initializeCoreBTypes (it was actually typo'ed as intializeCoreBTypes!)
   * - In 2.11.6+, including 2.12, we finally have
   *   genBCode.bTypes.initializeCoreBTypes
   * - As of 2.12, it is mandatory to call that method from GenJSCode.run()
   */

  object LowPrioGenBCodeCompat {
    object genBCode {
      object bTypes {
        def initializeCoreBTypes(): Unit = ()
      }
    }
  }

  def initializeCoreBTypesCompat(): Unit = {
    import LowPrioGenBCodeCompat._

    {
      import global._

      import LowPrioGenBCodeCompat.genBCode._

      {
        import genBCode._

        import LowPrioGenBCodeCompat.genBCode.bTypes._

        {
          import bTypes._

          initializeCoreBTypes()
        }
      }
    }
  }

  // Compat to support: new overridingPairs.Cursor(sym).iterator

  implicit class OverridingPairsCursor2Iterable(cursor: overridingPairs.Cursor) {
    def iterator: Iterator[SymbolPair] = new Iterator[SymbolPair] {
      skipIgnoredEntries()

      def hasNext: Boolean = cursor.hasNext

      def next(): SymbolPair = {
        val symbolPair = new SymbolPair(cursor.overriding, cursor.overridden)
        cursor.next()
        skipIgnoredEntries()
        symbolPair
      }

      private def skipIgnoredEntries(): Unit = {
        while (cursor.hasNext && ignoreNextEntry)
          cursor.next()
      }

      /** In 2.10 the overridingPairs.Cursor returns some false positives
       *  on overriding members. The known false positives are always trying to
       *  override the `isInstanceOf` method.
       */
      private def ignoreNextEntry: Boolean =
        cursor.overriding.name == nme.isInstanceOf_
    }

    class SymbolPair(val low: Symbol, val high: Symbol)

    /** To make this compat code compile in 2.11 as the fields `overriding` and
     *  `overridden` are only present in 2.10.
     */
    private implicit class Cursor210toCursor211(cursor: overridingPairs.Cursor) {
      def overriding: Symbol = infiniteLoop()
      def overridden: Symbol = infiniteLoop()
    }
  }

  // ErasedValueType has a different encoding

  implicit final class ErasedValueTypeCompat(self: global.ErasedValueType) {
    def valueClazz: Symbol = self.original.typeSymbol
    def erasedUnderlying: Type =
      enteringPhase(currentRun.erasurePhase)(
          erasure.erasedValueClassArg(self.original))
    def original: TypeRef = infiniteLoop()
  }

  // Definitions

  @inline final def repeatedToSingle(t: Type): Type =
    global.definitions.repeatedToSingle(t)

  final def isFunctionSymbol(sym: Symbol): Boolean =
    global.definitions.isFunctionSymbol(sym)

  private implicit final class DefinitionsCompat(
      self: Compat210Component.this.global.definitions.type) {

    def repeatedToSingle(t: Type): Type = t match {
      case TypeRef(_, self.RepeatedParamClass, arg :: Nil) => arg
      case _ => t
    }

    def isFunctionSymbol(sym: Symbol): Boolean =
      definitions.FunctionClass.seq.contains(definitions.unspecializedSymbol(sym))

  }

  // run.runDefinitions bundles methods and state related to the run
  // that were previously in definitions itself

  implicit final class RunCompat(self: Run) {
    val runDefinitions: Compat210Component.this.global.definitions.type =
      global.definitions
  }

  // Mode.FUNmode replaces analyzer.FUNmode

  object Mode {
    import Compat210Component.AnalyzerCompat
    // No type ascription! Type is different in 2.10 / 2.11
    val FUNmode = analyzer.FUNmode
  }
}

object Compat210Component {
  private object LowPriorityMode {
    object Mode {
      def FUNmode: Nothing = infiniteLoop()
    }
  }

  private implicit final class AnalyzerCompat(self: scala.tools.nsc.typechecker.Analyzer) {
    def FUNmode = { // scalastyle:ignore
      import Compat210Component.LowPriorityMode._
      {
        import scala.reflect.internal._
        Mode.FUNmode
      }
    }
  }

  private def infiniteLoop(): Nothing =
    throw new AssertionError("Infinite loop in Compat")

  private def noImplClasses(): Nothing =
    throw new AssertionError("No impl classes in this version")
}

trait PluginComponent210Compat extends Compat210Component {
  // Starting 2.11.x, we need to override the default description.
  def description: String
}
