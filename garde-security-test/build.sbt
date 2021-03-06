resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"                    % Garde.Akka      % "compile",
  "com.typesafe.akka" %% "akka-persistence-experimental" % Garde.Akka      % "compile",
  "org.scalaz"        %% "scalaz-core"                   % Garde.Scalaz    % "compile",
  "com.typesafe.akka" %% "akka-testkit"                  % Garde.Akka      % "test",
  "org.scalatest"     %% "scalatest"                     % Garde.ScalaTest % "test",
  "commons-io"         % "commons-io"                    % Garde.CommonsIo % "test"
)
