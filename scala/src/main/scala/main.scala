package main

import okhttp3.{OkHttpClient, Request}
import upickle.default.{macroRW, read, ReadWriter => RW}

// Prism models

case class PrismVPC(
    vpcId: String,
    accountId: String,
    isDefault: Boolean,
    subnets: List[PrismSubnet]
)

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
    bucketForArtifact: Option[String],
    bucketForPrivateConfig: Option[String],
    logging: Option[Logging],
    vpc: List[PrismVPC]
)

object AccountInfo {
  def asTypescriptTemplate(info: AccountInfo): String = {
    val camelCaseName = StringOps.hyphenToCamel(info.accountName)
    val primaryVpc = info.vpc.find(vpc => {
      val public = vpc.subnets.filter(_.isPublic)
      val privat = vpc.subnets.filter(!_.isPublic)
      public.length == 3 && privat.length == 3 && !vpc.isDefault
    })

    val vpcPart = primaryVpc match {
      case Some(vpc) =>
        val public =
          vpc.subnets.filter(_.isPublic).map(subnet => subnet.subnetId)
        val privat =
          vpc.subnets.filter(!_.isPublic).map(subnet => subnet.subnetId)

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

trait PrismLike {
  type AccountID = String

  def getAccounts(): List[PrismAccount]
  def getVpcs(): Map[AccountID, List[PrismVPC]]
}

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

object StringOps {
  def hyphenToCamel(name: String): String = {
    "-([a-z\\d])".r.replaceAllIn(
      name,
      { m =>
        m.group(1).toUpperCase()
      }
    )
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

    val accountsToMigate = Set("deploy-tools")

    val accountInfos = accounts
      .filter(account => accountsToMigate(account.accountName))
      .map(account =>
        AccountInfo(
          asT
        )
      )

    val tpls = accountInfos.map(AccountInfo.asTypescriptTemplate)
    tpls.foreach(println)
  }
}
