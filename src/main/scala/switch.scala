package numerato

import scala.reflect.macros.blackbox

object SwitchMacros {
  def switch_impl[E: c.WeakTypeTag, A: c.WeakTypeTag](c: blackbox.Context)(pf: c.Expr[PartialFunction[E, A]]): c.Expr[E => A] = {
    import c.universe._

    sealed abstract class MatchPattern(val exhaustive: Boolean) {
      def pos: Position
    }
    case class Wildcard(pos: Position, guarded: Boolean) extends MatchPattern(!guarded)
    case class Enum(pos: Position, name: TermName, guarded: Boolean) extends MatchPattern(!guarded)
    case class Other(pos: Position) extends MatchPattern(false)

    val etag = c.weakTypeTag[E]
    val companion = etag.tpe.typeSymbol.companion.asModule

    val values: Set[TermName] =
      companion.moduleClass.asType.toType.members
        .iterator
        .filter(_.isModule)
        .filter(_.asModule.moduleClass.asType.toType.baseClasses.contains(etag.tpe.typeSymbol.asType))
        .map(_.asModule.name).toSet

    val q"{ case ..$cases }" = pf.tree

    val matches: List[MatchPattern] = cases.flatMap {
      case CaseDef(pattern, guard, expr) =>
        val guarded = guard.nonEmpty
        pattern match {
          case pq"$enclosing.$member" =>
            Enum(pattern.pos, member, guarded) :: Nil
          case pq"$enclosing.$member | ..$altern" =>
            Enum(pattern.pos, member, guarded) :: altern.collect {
              case alt @ pq"$enclosing.$member" => Enum(alt.pos, member, guarded)
            }
          case wc @ pq"_" => Wildcard(wc.pos, guarded) :: Nil
          case other => Other(other.pos) :: Nil
        }
    }

    val uncovered = values -- matches.collect { case m @ Enum(_, name, _) if m.exhaustive => name }.toSet

    if (matches.exists(!_.exhaustive)) {
      matches.find(!_.exhaustive).foreach {
        case Wildcard(pos, _) => c.error(pos, "potentially incomplete wildcard match - guards not allowed")
        case Enum(pos, name, _) => c.error(pos, s"potentially incomplete `$name` match - guards not allowed")
        case Other(pos) => c.error(pos, "potentially incomplete match")
      }
    } else if (uncovered.nonEmpty && !matches.collect { case wc: Wildcard => wc }.exists(_.exhaustive)) {
      val names = uncovered.map { case TermName(name) => name }.toList.sorted.mkString(", ")
      c.error(
        pf.tree.pos,
        s"not all values of ${etag.tpe} are covered: $names"
      )
    }

    c.Expr(q"""(e: $etag) => ${pf}.lift.apply(e).getOrElse(???)""")
  }
}
