name := "garde"

version in ThisBuild := Garde.Version

lazy val security = Project("garde-security", file("garde-security"))

lazy val securityTest = Project("garde-security-test", file("garde-security-test")).configs(IntegrationTest).settings(Defaults.itSettings: _*).dependsOn(security)

lazy val http = Project("garde-http", file("garde-http")).dependsOn(security)

parallelExecution in Test := false

parallelExecution in IntegrationTest := false
