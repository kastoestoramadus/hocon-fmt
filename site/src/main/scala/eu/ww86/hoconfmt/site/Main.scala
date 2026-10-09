package eu.ww86.hoconfmt.site

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Mounts the page into #root of index.html; see docs/site.md. */
@main def main(): Unit = {
  // The page lives as long as the tab, so the subscription is never cancelled.
  val _ = documentEvents(_.onDomContentLoaded).foreach { _ =>
    Option(dom.document.getElementById("root")).foreach { container =>
      container.innerHTML = ""
      render(container, Page())
    }
  }(using unsafeWindowOwner)
}
