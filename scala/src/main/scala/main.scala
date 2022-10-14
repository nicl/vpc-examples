package main

import okhttp3.{OkHttpClient, Request}
import upickle.default.{macroRW, read, ReadWriter => RW}

// Prism models

// We use case classes to model the JSON Prism response. JSON handling in Scala
// is a bit of a pain because there is no 'standard' JSON library and many of
// the popular ones are overly complex. Here I've used 'uPickle'. For an
// interesting read, see:
// https://www.lihaoyi.com/post/uJsonfastflexibleandintuitiveJSONforScala.html.
case class PrismVPC(
    vpcId: String,
    accountId: String,
    default: Boolean,
    subnets: List[PrismSubnet]
)

// Common to most JSON libraries is the use of macros to support JSON
// (de)serialisation.
object PrismVPC {
  implicit val rw: RW[PrismVPC] = macroRW
}

case class PrismSubnet(
    isPublic: Boolean,
    subnetId: String
)

object PrismSubnet {
  implicit val rw: RW[PrismSubnet] = macroRW
}

case class PrismAccount(accountNumber: String, accountName: String)
object PrismAccount {
  implicit val rw: RW[PrismAccount] = macroRW
}

case class PrismResponseAccountsWrapper(data: List[PrismAccount])
object PrismResponseAccountsWrapper {
  implicit val rw: RW[PrismResponseAccountsWrapper] = macroRW
}

case class PrismVPCs(vpcs: List[PrismVPC])
object PrismVPCs {
  implicit val rw: RW[PrismVPCs] = macroRW
}

case class PrismResponseVPCsWrapper(data: PrismVPCs)
object PrismResponseVPCsWrapper {
  implicit val rw: RW[PrismResponseVPCsWrapper] = macroRW
}

// Internal models

case class Logging(streamName: String)

case class AccountInfo(
    accountNumber: String,
    accountName: String,
    stack: String,
    // Option is used for things that may or may not exist - 'Some' and 'None'.
    bucketForArtifact: Option[String],
    bucketForPrivateConfig: Option[String],
    logging: Option[Logging],
    vpc: List[PrismVPC]
)

// Scala doesn't support 'free-floating' functions; everything has to live in a
// class. Fortunately, it does have `object` which denotes a singleton object.
// This is great for acting as a namespace for 'pure' functions (technically
// they are 'methods'). Most of your methods should be defined in this way. And
// it worth avoiding (non-case) classes unless you really need mutable state.
object AccountInfo {
  def asTypescriptTemplate(info: AccountInfo): String = {
    val camelCaseName = StringOps.hyphenToCamel(info.accountName)
    val primaryVpc = info.vpc.find(vpc => {
      val public = vpc.subnets.filter(_.isPublic)
      val privat = vpc.subnets.filter(!_.isPublic)
      public.length == 3 && privat.length == 3 && !vpc.default
    })

    val vpcPart = primaryVpc match {
      case Some(vpc) =>
        val public =
          vpc.subnets.filter(_.isPublic).map(subnet => subnet.subnetId)
        val privat =
          vpc.subnets.filter(!_.isPublic).map(subnet => subnet.subnetId)

        // Scala string interpolation is great here!
        s"""vpc: {
           |  primary: {
           |    privateSubnets: ${privat.mkString("['", "', '", "']")}
           |    publicSubnets: ${public.mkString("['", "', '", "']")}
           |  }
           |}
           |
           |""".stripMargin
      case None =>
        "// No matching VPC was found!"
    }

    s"""import type { AwsAccountSetupProps } from '../types';
       |
       |export const ${camelCaseName}Account: AwsAccountSetupProps = {
       |  accountNumber: '${info.accountNumber}',
       |  accountName: '${camelCaseName}',
       |  stack: '${info.accountName}',
       |  bucketForArtifacts: 'TODO',
       |  bucketForPrivateConfig: 'TODO',
       |  logging: {
       |    streamName: 'TODO',
       |  }
       |${StringOps.indent(vpcPart, 2)}
       |}
       |""".stripMargin
  }
}

// The trait is overkill here but it allows for easier testing if that was
// required - because we can substitute a stub when writing our tests to avoid
// network calls. See: https://martinfowler.com/articles/mocksArentStubs.html. I
// recommend avoiding mocks and using 'fakes' and 'stubs' whenever possible in
// tests!
trait PrismLike {
  type AccountID = String

  def getAccounts(): List[PrismAccount]
  def getVpcs(): Map[AccountID, List[PrismVPC]]
}

// I've used 'OkHttpClient' here for HTTP requests. A bit like for JSON, there
// isn't a standard Scala library here unfortunately. OkHttpClient is a
// reasonable choice though and fairly easy to use.
case class Prism(client: OkHttpClient) extends PrismLike {
  override def getAccounts(): List[PrismAccount] = {
    val url = "https://prism.gutools.co.uk/sources/accounts"
    val req = new Request.Builder().url(url).build()
    val resp = client.newCall(req).execute().body.string()

    read[PrismResponseAccountsWrapper](resp).data
  }

  override def getVpcs(): Map[
    AccountID,
    List[PrismVPC]
  ] = {
    val url = s"https://prism.gutools.co.uk/vpcs"
    val req = new Request.Builder().url(url).build()
    val resp = client.newCall(req).execute().body.string()

    val vpcs = read[PrismResponseVPCsWrapper](resp).data.vpcs

    vpcs.groupBy(vpc => vpc.accountId)
  }
}

// Again we use 'object' to house helper functions here.
object StringOps {
  def hyphenToCamel(name: String): String = {
    name.split("-").map(_.capitalize).mkString
  }

  def indent(str: String, spaces: Int): String = {
    str.lines().map(line => s"${" " * spaces}$line").toArray.mkString("\n")
  }
}

// Example code approach
object Main {
  def main(args: Array[String]): Unit = {
    val httpClient = new OkHttpClient()
    val prism = Prism(httpClient)
    val accounts = prism.getAccounts()
    val vpcs = prism.getVpcs()

    val accountsToMigate = Set("deploy-tools") // subset to illustrate

    val accountInfos = accounts
      .filter(account => accountsToMigate(account.accountName))
      .map(account =>
        AccountInfo(
          accountNumber = account.accountNumber,
          accountName = StringOps.hyphenToCamel(account.accountName),
          stack = account.accountName,
          bucketForArtifact = Some("TODO"),
          bucketForPrivateConfig = Some("TODO"),
          logging = Some(Logging(streamName = "TODO")),
          vpc = vpcs.getOrElse(account.accountNumber, Nil)
        )
      )

    val tpls = accountInfos.map(AccountInfo.asTypescriptTemplate)
    tpls.foreach(println)
  }
}
