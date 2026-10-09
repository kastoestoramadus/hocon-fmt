sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("eu.ww86" % "sbt-hocon-fmt" % version)
  case None          => sys.error("Run through `scripted`, which passes the plugin version as -Dplugin.version.")
}
