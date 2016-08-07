package numerato

import scala.annotation.StaticAnnotation
import scala.reflect.macros.whitebox
import scala.tools.nsc.Global
import scala.tools.nsc.ast.DocComments
import scala.tools.nsc.ast.Trees

class enum(debug: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro EnumMacros.decl
}

private[numerato] class EnumMacros(val c: whitebox.Context) {
  import c.universe._

  /**
   * The internal compiler API, which we need for certain operations not supported by the macro API.
   */
  private val global = c.universe.asInstanceOf[Global]

  private[EnumMacros] class ValueDecl(val name: TermName, val args: List[Tree], val pos: Position,
    val comment: Option[global.DocComment] = None)

  private[EnumMacros] class EnumDeclaration(
      val enumType: TypeName,
      val params: List[ValDef],
      val valueDecls: Seq[ValueDecl],
      val mods: Modifiers,
      val parents: List[Tree],
      val pos: Position
  ) {
    val values = valueDecls.map(_.name)

    private val reservedNames = Set("index", "name")
    def validate = {
      params.foreach {
        case param @ q"$mods val ${ TermName(name) }: $tpe = $expr" =>
          if (reservedNames.contains(name))
            c.error(param.pos, s"`$name` is reserved & can not be used as a enum field")
      }
    }

    // NB: The specific form of the generated class and object are known in EnumMacros._.
    private val baseMods = Modifiers(mods.flags | Flag.SEALED | Flag.ABSTRACT, mods.privateWithin, mods.annotations)
    protected def base =
      q"""
        $baseMods class $enumType(..$params, val index: Int, val name: String)(implicit sealant: ${enumType.toTermName}.Sealant) extends ..$parents with Serializable
      """

    protected def value(name: TermName, index: Int): Tree = {
      val vd = valueDecls.find(_.name == name).getOrElse(???)
      val decl = setPos(q"""
        case object $name extends $enumType(..${vd.args}, $index, ${s"$name"})
      """, vd.pos)
      if (vd.comment.isEmpty) {
        decl
      } else {
        new global.DocDef(vd.comment.get, decl.asInstanceOf[global.Tree]).asInstanceOf[Tree]
      }
    }

    protected lazy val lookups: List[Tree] =
      q"""
        val fromIndex: Int => $enumType = Map(..${values.map(value => q"$value.index -> $value")})
      """ ::
        q"""
        val fromName: String => $enumType = Map(..${values.map(value => q"$value.name -> $value")})
      """ :: Nil

    protected lazy val bodyParts = values.zipWithIndex.map {
      case (name, index) => value(name, index)
    }

    lazy val result = {
      // NB: The specific form of the generated class and object are known in EnumMacros._.
      val baseClass = q"$base"
      val enumObject = q"""
        $mods object ${enumType.toTermName} {
          @scala.annotation.implicitNotFound(msg = "Enum types annotated with " +
            "@enum can not be extended directly. To add another value to the enum, " +
            "please adjust your `def ... = Value` declaration.")
          protected sealed abstract class Sealant
          protected implicit object Sealant extends Sealant
            ..$bodyParts
            val values: List[$enumType] = List(..$values)
            ..$lookups
            def switch[A](pf: PartialFunction[$enumType, A]): $enumType => A =
              macro numerato.SwitchMacros.switch_impl[$enumType, A]
        }
      """
      c.internal.setPos(baseClass, pos)
      c.internal.setPos(enumObject, pos)
      q"$baseClass;$enumObject"
    }
  }

  private def declaredParams(params: List[ValDef]): List[ValDef] =
    params.map {
      case q"$mods val $pname: $ptype = $pdefault" =>
        q"val $pname: $ptype = $pdefault"
    }

  /**
   * Converts all or a portion of the given `body` of an @enum class into a sequence of `ValueDecl`s,
   * generating errors as appropriate and preserving ScalaDoc comments.
   */
  private def valueDeclarations(body: Seq[Tree]): Seq[ValueDecl] = {
    body.flatMap {
      case v @ q"""val $value = Value(..$vparams)""" =>
        Some(new ValueDecl(value, vparams, v.pos))

      case v @ q"""val $value = Value""" =>
        Some(new ValueDecl(value, Nil, v.pos))

      // Need to handle a definition wrapped in a Scaladoc comment. Unfortunately, the macro API
      // doesn't support DocDefs, so we have to use lots of casts to deal with the path-dependent
      // types here.
      case d if d.isInstanceOf[Trees#DocDef] => {
        val docDef = d.asInstanceOf[global.DocDef]
        val valueDecls = valueDeclarations(docDef.definition.asInstanceOf[c.universe.Tree] :: Nil)
        if (valueDecls.isEmpty) {
          None
        } else {
          Some(new ValueDecl(valueDecls.head.name, valueDecls.head.args, valueDecls.head.pos,
            Some(docDef.comment)))
        }
      }

      case x @ _ => {
        c.error(x.pos, "@enum body may contain only value declarations")
        None
      }
    }
  }

  def decl(annottees: Tree*): Tree = {
    val debug = c.prefix.tree match {
      case q"new enum(..$params)" =>
        params.collect {
          case q"debug = $d" => d
          case q"$d" => d
        }.headOption.map {
          case q"false" => false
          case q"true" => true
        }.getOrElse(false)
      case q"new enum()" => false
      case _ => sys.error(showCode(c.prefix.tree))
    }

    val decl: EnumDeclaration =
      annottees match {
        case tree @ List(q"$mods class $enumType extends ..$parents { ..$body }") =>
          new EnumDeclaration(
            enumType = enumType,
            params = Nil,
            valueDecls = valueDeclarations(body),
            mods = mods,
            parents = parents,
            pos = tree.head.pos
          )
        case tree @ List(q"$mods class $enumType(..$params) extends ..$parents { ..$body }") =>
          new EnumDeclaration(
            enumType = enumType,
            params = declaredParams(params),
            valueDecls = valueDeclarations(body),
            mods = mods,
            parents = parents,
            pos = tree.head.pos
          )
        case x @ List(_) => { c.abort(annottees.head.pos, s"unsupported form of class declaration for @enum: ${x.head.getClass}"); return annottees.head }
        case _ => { c.abort(annottees.head.pos, "unsupported form of class declaration for @enum"); return annottees.head }
      }
    if (debug) println(showCode(decl.result))
    decl.validate
    decl.result
  }

  /**
   * Sets the given `position` on `tree` and each of its descendant trees.
   *
   * @return `tree`
   */
  private[EnumMacros] def setPos(tree: Tree, position: Position): Tree = {
    val t = c.internal.setPos(tree, position)
    t.children.foreach { t => setPos(t, position) }
    t
  }
}

/**
 * `EnumMacros` provides operations to examine the internal compiler symbols and trees generated by this macro.
 */
object EnumMacros {
  import scala.reflect.internal.Flags
  import scala.reflect.internal.Symbols

  /**
   * Returns true if the given symbol represents either a class or module that was generated to
   * represent an enumeration as part of the expansion of an `@enum` macro.
   */
  def isEnumGenerated(symbol: Symbols#Symbol): Boolean = {
    // We'd like to use attachments to pass information directly on the symbol, which would give us
    // a foolproof means to detect this, but at Scala 2.11.8 with macroparadise 2.1.0, attachments
    // added to the result of an annotation macro are discarded, so just run a heuristic to check for
    // the expected forms.

    if (symbol.isAbstractClass && symbol.isSealed) {
      val constructor = symbol.primaryConstructor
      val companion = symbol.companion.moduleClass
      companion.isModuleClass &&
        constructor.typeParams.isEmpty &&
        constructor.paramss.size == 2 &&
        constructor.paramss(1).size == 1 &&
        constructor.paramss(1)(0).name.toString == "sealant" &&
        constructor.paramss(1)(0).tpe.typeSymbol.name.toString == "Sealant" &&
        constructor.paramss(1)(0).tpe.typeSymbol.owner == companion &&
        symbol.children.forall(c => c.isModuleClass && c.owner == companion)
    } else if (symbol.isModule) {
      isEnumGenerated(symbol.companionClass)
    } else false
  }

  /**
   * Returns true if the given symbol represents an enumerator constant generated as part of the expansion
   * of an `@enum` macro.
   */
  def isEnumeratorGenerated(symbol: Symbols#Symbol): Boolean = {
    // Enumerators are case objects within the enum object that extend the enum class.
    symbol.isModule && isEnumGenerated(symbol.owner.module) &&
      symbol.moduleClass.ancestors.contains(symbol.owner.module.companionClass)
  }

  /**
   * Analyzes the AST of the output of this macro and returns a list of the enumerators with their associated
   * initializer values, as provided to the `Value` method in the declaration of the enuemration.
   *
   * @param tree the tree that is the output of this macro
   * @return a list of enumerator names along with the trees representing the arguments passed to the `Value` method
   *         in each of their declarations
   *
   * @pre // tree is the generated output tree of this macro, or the portion of it that defines the enumeration
   *     `object`, either before or after processing by the typer
   */
  def extractEnumerators(tree: Trees#Tree): List[Tuple2[String, List[Trees#Tree]]] = {
    val moduleDef =
      if (tree.isInstanceOf[Trees#Block] && tree.asInstanceOf[Trees#Block].stats.size == 2) {
        tree.asInstanceOf[Trees#Block].stats(1).asInstanceOf[Trees#ModuleDef]
      } else if (tree.isInstanceOf[Trees#ModuleDef]) {
        tree.asInstanceOf[Trees#ModuleDef]
      } else {
        throw new AssertionError // precondition failure
      }

    moduleDef.impl.body.flatMap {
      case docDef if docDef.isInstanceOf[Trees#DocDef] => {
        val defn = docDef.asInstanceOf[Trees#DocDef].definition
        // Quasiquotes pattern match fails here.
        if (defn.isInstanceOf[Trees#ModuleDef] && defn.asInstanceOf[Trees#ModuleDef].mods.hasFlag(Flags.CASE)) {
          Some((defn.asInstanceOf[Trees#ModuleDef].name.toString, enumeratorParams(defn)))
        } else {
          None
        }
      }
      case defn if defn.isInstanceOf[Trees#ModuleDef] && defn.asInstanceOf[Trees#ModuleDef].mods.hasFlag(Flags.CASE) => {
        Some((defn.asInstanceOf[Trees#ModuleDef].name.toString, enumeratorParams(defn)))
      }
      case _ => None
    }
  }

  /**
   * Returns the arguments provided to the `Value` method in the declaration of the enumerator constant defined in the
   * given generated tree.
   *
   * @pre // tree is the generated definition of an enumerator, either before or after processing by the typer
   */
  private def enumeratorParams(tree: Trees#Tree): List[Trees#Tree] = {
    // There are two possible representations, depending on whether this is the direct output of the macro or the same
    // tree after the compiler typer has further processed it and inserted it into the body of the constructor.
    val module = tree.asInstanceOf[Trees#ModuleDef]

    if (module.impl.parents.size > 0 && module.impl.parents.head.isInstanceOf[Trees#Apply]) {
      val superCall = module.impl.parents.head.asInstanceOf[Trees#Apply]

      // Last two args are "index" and "name", which are added by macro.
      assert(superCall.args.size >= 2)

      superCall.args.slice(0, superCall.args.size - 2)
    } else {
      val constructor = module.impl.body.head.asInstanceOf[Trees#DefDef]
      assert(constructor.name.toString == "<init>")

      val superCall = constructor.rhs.asInstanceOf[Trees#Block].stats.head.asInstanceOf[Trees#Apply]
      // Outer call adds implicit Sealant arg to curried inner call.
      val innerCall = superCall.fun.asInstanceOf[Trees#Apply]
      assert(innerCall.fun.asInstanceOf[Trees#Select].name.toString == "<init>")

      // Last two args are "index" and "name", which are added by macro.
      assert(innerCall.args.size >= 2)

      innerCall.args.slice(0, innerCall.args.size - 2)
    }
  }
}