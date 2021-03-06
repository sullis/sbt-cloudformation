import bintray.Keys._

sbtPlugin := true

name := "sbt-cloudformation"

organization := "com.github.tptodorov"

version := "0.3.0"

scalaVersion := "2.10.4"

libraryDependencies += "com.amazonaws" % "aws-java-sdk-cloudformation" % "1.9.29"

publishTo <<= (version) { version: String =>
   val scalasbt = "http://scalasbt.artifactoryonline.com/scalasbt/"
   val (name, url) = if (version.contains("-SNAPSHOT"))
                       ("sbt-plugin-snapshots-publish", scalasbt+"sbt-plugin-snapshots")
                     else
                       ("sbt-plugin-releases-publish", scalasbt+"sbt-plugin-releases")
   Some(Resolver.url(name, new URL(url))(Resolver.ivyStylePatterns))
}

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

scalacOptions += "-deprecation"

crossBuildingSettings

CrossBuilding.crossSbtVersions := Seq("0.13")

CrossBuilding.scriptedSettings

scriptedLaunchOpts <<= (scriptedLaunchOpts, version) { case (s,v) => s ++
  Seq("-Xmx1024M", "-XX:MaxPermSize=256M", "-Dplugin.version=" + v)
}

scriptedBufferLog := false

bintraySettings

packageLabels in bintray := Seq("aws", "cloudformation")

publishMavenStyle := false

repository in bintray := "sbt-plugins"

bintrayOrganization in bintray := None
