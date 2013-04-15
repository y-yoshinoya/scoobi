package com.nicta.scoobi
package application

import org.apache.hadoop.fs._
import scala.tools.nsc.Settings
import tools.nsc.interpreter.{ReplReporter, AbstractFileClassLoader, ILoop}
import core.ScoobiConfiguration
import scala.collection.JavaConversions._
import tools.nsc.reporters.ConsoleReporter
import tools.nsc.interpreter.IMain.ReplStrippingWriter
import tools.nsc.util.ScalaClassLoader.URLClassLoader

/** A REPL for Scoobi.
  *
  * Run the 'scoobi' script, which will bring you into the Scala REPL
  *
  * You're now good to go!! */
object ScoobiRepl extends ScoobiInterpreter

/** This trait can be extended if you want to provide alternative imports or behaviors
  *
  */
trait ScoobiInterpreter extends ScoobiApp with ReplFunctions {
  override def main(arguments: Array[String]) {
    parseHadoopArguments(arguments)
    try { run }
    finally { if (!keepFiles) { configuration.deleteWorkingDirectory } }
  }

  /** Don't upload jars by default */
  override lazy val upload = false

  def run() {
    val settings = new Settings
    settings.usejavacp.value = true
    setConfiguration(configuration.configuration)
    new ScoobiILoop(this).process(settings)
  }

  def help =
    """|
       |Available commands:
       |
       | help               the list of commands
       | inmemory           set the execution mode to in-memory
       | local              set the execution mode to local
       | cluster (default)  set the execution mode to cluster (using the configuration from $HADOOP_HOME/conf)
       |
       |""".stripMargin

  /** set the configuration so that the next job is run in memory */
  def inmemory {
    setNewArguments("inmemory" +: removeExecutionMode(replArgs))
  }
  /** set the configuration so that the next job is run locally */
  def local {
    setNewArguments("local" +: removeExecutionMode(replArgs))
  }
  /** set the configuration so that the next job is run on the cluster - this is the default */
  def cluster  {
    setNewArguments("cluster" +: removeExecutionMode(replArgs))
  }

  def removeExecutionMode(arguments: Seq[String]) =
    arguments.filterNot(Seq("local", "cluster", "inmemory").contains)

  private[scoobi] def setDefaultArgs {
    replArgs = Seq("verbose", "all")
    setNewArguments(replArgs)
  }

  def scoobiArgs_=(arguments: String) {
    setNewArguments(arguments.split("\\.").map(_.trim))
  }

  private def setNewArguments(arguments: Seq[String]) {
    replArgs = arguments
    scoobiArguments = replArgs

    if (isInMemory)   configureForInMemory
    else if (isLocal) configureForLocal
    else              configureForCluster
    setLogFactory()
  }

  private var replArgs: Seq[String] = Seq()
  override def argumentsValues = replArgs
  override def useHadoopConfDir = true

}

/**
 * definition of the interpreter loop
 */
class ScoobiILoop(scoobiInterpreter: ScoobiInterpreter) extends ILoop { outer =>
  val configuration = scoobiInterpreter.configuration

  def imports: Seq[String] = Seq(
    "com.nicta.scoobi.Scoobi._",
    "com.nicta.scoobi.application.ScoobiRepl._",
    "scala.collection.JavaConversions._")

  override def prompt = if (intp.isInitializeComplete) "\nscoobi> " else ""
  override def printWelcome() {
    println("\n === Please wait while Scoobi is initialising... ===")
  }

  /** truncate the result if it is some Scoobi datatypes */
  def truncateResult(result: String) = {
    if (Seq("DList", "DObject").map("com.nicta.scoobi.core."+_).exists(result.contains)) result.split("=").head + "= ..."
    else result
  }

  addThunk {
    // create a new interpreter which will strip the output with a special function
    intp = new ILoopInterpreter {
      def strippingWriter = new ReplStrippingWriter(outer) {
        override def truncate(str: String): String = {
          val truncated =
            if (isTruncating && str.length > maxStringLength) (str take maxStringLength - 3) + "..."
            else str
          truncateResult(truncated)
        }
      }
      override lazy val reporter = new ReplReporter(outer) {
        val realReporter = new ConsoleReporter(outer.settings, null, strippingWriter)
        override def printMessage(message: String) { realReporter.printMessage(message) }
        override def displayPrompt() { realReporter.displayPrompt() }
        override def flush() { realReporter.flush() }
      }
    }
    intp.beQuietDuring {
      imports.foreach(i => intp.addImports(i))
      configuration.addClassLoader(intp.classLoader)
      configuration.jobNameIs("REPL")
      scoobiInterpreter.cluster
      scoobiInterpreter.setDefaultArgs
      woof()
    }
  }

  /** announce that the Scoobi REPL is ready */
  def woof() {
    println(splash)
    println("\n\n === Ready, press Enter to start ===")
  }

  lazy val splash: String =
    """|
       |                                                     |\
       |                                             /    /\/o\_
       |                              __    _       (.-.__.(   __o
       |       ______________  ____  / /_  (_)   /\_(      .----'
       |      / ___/ ___/ __ \/ __ \/ __ \/ /     .' \____/
       |     (__  ) /__/ /_/ / /_/ / /_/ / /     /   /  / \
       |    /____/\___/\____/\____/_.___/_/ ____:____\__\__\____________""".stripMargin

}

trait ReplFunctions { this: { def configuration: ScoobiConfiguration } =>
  /** List a path . */
  def ls(path: String) {
    configuration.fileSystem.listStatus(new Path(path)) foreach { fstat =>
      Console.printf("%s%s  %-15s  %-12s  %s\n",
        if (fstat.isDir) "d" else "-",
        fstat.getPermission,
        fstat.getOwner,
        fstat.getBlockSize,
        fstat.getPath.toUri.getPath)
    }
  }

  /** Get the contents of a text file. */
  def cat(path: String): Iterable[String] = {
    import scala.io._
    Source.fromInputStream(configuration.fileSystem.open(new Path(path))).getLines().toIterable
  }

}

