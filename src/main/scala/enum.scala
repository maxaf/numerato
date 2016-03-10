package numerato

import scala.annotation.StaticAnnotation
import scala.reflect.macros.whitebox

class enum(debug: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro EnumMacros.decl
}

class EnumMacros(val c: whitebox.Context) {
  import c.universe._

  sealed trait EnumDeclaration {
    val enumType: TypeName
    val values: List[TermName]

    def validate: Unit
    protected def base: Tree
    protected def value(name: TermName, index: Int): Tree

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

  class PlainEnumDeclaration(
      val enumType: TypeName,
      val values: List[TermName]
  ) extends EnumDeclaration {
    def validate = ()

    protected val base: Tree =
      q"""
        sealed abstract class $enumType(val index: Int, val name: String)(implicit sealant: ${enumType.toTermName}.Sealant)
      """

    protected def value(name: TermName, index: Int): Tree =
      q"""case object $name extends $enumType($index, ${s"$name"})"""
  }

  class ValueDecl(val name: TermName, val args: List[Tree])

  class ParametricEnumDeclaration(
      val enumType: TypeName,
      val params: List[ValDef],
      val valueDecls: List[ValueDecl]
  ) extends EnumDeclaration {
    val values = valueDecls.map(_.name)

    private val reservedNames = Set("index", "name")
    def validate = {
      params.foreach {
        case param @ q"$mods val ${ TermName(name) }: $tpe = $expr" =>
          if (reservedNames.contains(name))
            c.error(param.pos, s"`$name` is reserved & can not be used as a enum field")
      }
    }

    protected def base =
      q"""
        sealed abstract class $enumType(..$params, val index: Int, val name: String)(implicit sealant: ${enumType.toTermName}.Sealant)
      """

    protected def value(name: TermName, index: Int): Tree = {
      val vd = valueDecls.find(_.name == name).getOrElse(???)
      q"""
        case object $name extends $enumType(..${vd.args}, $index, ${s"$name"})
      """
    }
  }

  private def declaredParams(params: List[ValDef]): List[ValDef] =
    params.map {
      case q"$mods val $pname: $ptype = $pdefault" =>
        q"val $pname: $ptype = $pdefault"
    }

  def decl(annottees: c.Expr[Any]*) = {
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
      annottees.map(_.tree) match {
        case tree @ List(q"class $enumType { ..$body }") =>
          new PlainEnumDeclaration(enumType, body.flatMap {
            case q"""val $value = Value""" => value :: Nil
            case _ => Nil
          })
        case tree @ List(q"class $enumType(..$params) { ..$body }") =>
          new ParametricEnumDeclaration(
            enumType = enumType,
            params = declaredParams(params),
            valueDecls = body.collect {
              case q"""val $value = Value(..$vparams)""" =>
                new ValueDecl(value, vparams)
            }
          )
      }
    if (debug) println(showCode(decl.result))
    decl.validate
    decl.result
  }
}
