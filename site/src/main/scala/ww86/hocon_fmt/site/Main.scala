package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

/** Mounts the page into #root of index.html; see docs/site.md. */
@main def main(): Unit =
  documentEvents(_.onDomContentLoaded).foreach { _ =>
    Option(dom.document.getElementById("root")).foreach { container =>
      container.innerHTML = ""
      render(container, Page())
    }
  }(using unsafeWindowOwner)
