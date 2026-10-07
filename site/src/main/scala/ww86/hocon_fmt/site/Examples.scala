package ww86.hocon_fmt.site

/** The five playground examples, one button each. Every one carries a test that runs it through
  * the core on Scala.js and pins the verdict, so an example cannot quietly stop showing what its
  * label promises.
  */
final case class Example(id: String, label: String, shows: String, source: String)

object Examples:

  val messy = Example(
    "messy",
    "A messy config",
    "indentation, spacing and the nested object get tidied; the meaning does not change",
    """app {
    name  =  "svc"
   port =8080
  db { url = "jdbc:postgresql://localhost/app" }
}"""
  )

  val includes = Example(
    "includes",
    "Includes that survive",
    "the include directive is still there after formatting, where a plain parse-render round trip deletes it",
    """app {
    name  =  "svc"
   port =8080
  include "local.conf"
  db { url = "jdbc:postgresql://localhost/app" }
}"""
  )

  val comments = Example(
    "comments",
    "Comments kept",
    "every comment survives, and one at the end of a line moves above",
    """# service defaults
app {
  # the service name
  name = svc   # said twice, never enough
  port = 8080
}
# the db runs where you least expect it
db {
  url = "jdbc:postgresql://localhost/app"
}"""
  )

  val notHocon = Example(
    "not-hocon",
    "Not HOCON at all",
    "an nginx config is refused as not HOCON and left unchanged",
    """server {
    listen 80;
    server_name example.com;
    root /var/www/html;
}"""
  )

  val sconfigDefect = Example(
    "sconfig-defect",
    "A known sconfig defect",
    "sconfig renders this as text it cannot read back, so the formatter refuses it — one of the bugs listed in the contributions below",
    """a : [1]
a += 2"""
  )

  val all: List[Example] = List(messy, includes, comments, notHocon, sconfigDefect)
