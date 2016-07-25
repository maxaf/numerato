package numerato

import scala.annotation.StaticAnnotation
import scala.reflect.macros.whitebox

class enum(debug: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro EnumMacros.decl
}

private[numerato] class EnumMacros(val c: whitebox.Context) {
  import c.universe._

  private[EnumMacros] class ValueDecl(val name: TermName, val args: List[Tree], val pos: Position)

  private[EnumMacros] class EnumDeclaration(
      val enumType: TypeName,
      val params: List[ValDef],
      val valueDecls: List[ValueDecl],
      val mods: Modifiers,
      val parents: List[Tree]
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

    private val newMods = Modifiers(mods.flags | Flag.SEALED | Flag.ABSTRACT, mods.privateWithin, mods.annotations)
    protected def base =
      q"""
        $newMods class $enumType(..$params, val index: Int, val name: String)(implicit sealant: ${enumType.toTermName}.Sealant) extends ..$parents with Serializable
      """

    protected def value(name: TermName, index: Int): Tree = {
      val vd = valueDecls.find(_.name == name).getOrElse(???)
      setPos(q"""
        case object $name extends $enumType(..${vd.args}, $index, ${s"$name"})
      """, vd.pos)
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

    lazy val result = q"""
      $base
      object ${enumType.toTermName} {
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
  }

  private def declaredParams(params: List[ValDef]): List[ValDef] =
    params.map {
      case q"$mods val $pname: $ptype = $pdefault" =>
        q"val $pname: $ptype = $pdefault"
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
            valueDecls = body.collect {
              case v @ q"""val $value = Value(..$vparams)""" =>
                new ValueDecl(value, vparams, v.pos)
              case v @ q"""val $value = Value""" =>
                new ValueDecl(value, Nil, v.pos)
            },
            mods = mods,
            parents = parents
          )
        case tree @ List(q"$mods class $enumType(..$params) extends ..$parents { ..$body }") =>
          new EnumDeclaration(
            enumType = enumType,
            params = declaredParams(params),
            valueDecls = body.collect {
              case v @ q"""val $value = Value(..$vparams)""" =>
                new ValueDecl(value, vparams, v.pos)
              case v @ q"""val $value = Value""" =>
                new ValueDecl(value, Nil, v.pos)
            },
            mods = mods,
            parents = parents
          )
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
