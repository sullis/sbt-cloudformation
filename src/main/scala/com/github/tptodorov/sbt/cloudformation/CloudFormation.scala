package com.github.tptodorov.sbt.cloudformation

import java.io.FileNotFoundException
import java.util

import com.amazonaws.auth.{AWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.regions._
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model._
import sbt.Keys._
import sbt._

import scala.collection.convert.WrapAsJava._
import scala.collection.convert.WrapAsScala._
import scala.collection.immutable.Iterable
import scala.util.{Failure, Try}

object Import {

  object Configurations {
    lazy val Production = config("production")
    lazy val Staging = config("staging")
  }

  object Keys {

    type Parameters = Map[String, String]

    // for all configurations
    val awsCredentials = settingKey[AWSCredentials]("AWS credentials")
    val templatesSourceFolder = settingKey[File]("folder where CloudFormation templates are")
    val templates = settingKey[Seq[File]]("template sources")

    // in each configuration
    val stackTemplate = settingKey[String]("default template to use for this configuration")
    val stackParams = settingKey[Parameters]("Parameters applied to the template for this configuration")
    val stackCapabilities = settingKey[Seq[String]]("The list of capabilities that you want to allow in the stack . E.g.[CAPABILITY_IAM]")
    val stackRegion = settingKey[String]("The region where the stacks are deployed. E.g. eu-west-1 ")
    val stackName = settingKey[String]("stack name")


    // stack operations
    val stackValidate = taskKey[Seq[File]]("validate templates")
    val stackDescribe = taskKey[Unit]("describe stack completely")
    val stackStatus = taskKey[Unit]("describe stack status")
    val stackCreate = taskKey[String]("create a stack and returns its stackId")
    val stackDelete = taskKey[Unit]("delete a stack")
    val stackUpdate = taskKey[String]("update a stack")

    val stackClient = settingKey[AmazonCloudFormationClient]("AWS CloudFormation Client")
  }

}

object CloudFormation extends sbt.Plugin {


  import com.github.tptodorov.sbt.cloudformation.Import.Configurations._
  import com.github.tptodorov.sbt.cloudformation.Import.Keys._

  private lazy val awsCredentialsProvider = new DefaultAWSCredentialsProviderChain()

  lazy val validationSettings = Seq(

    templatesSourceFolder <<= baseDirectory {
      base => base / "src/main/aws"
    },
    templates := {
      val templates = templatesSourceFolder.value ** GlobFilter("*.template")
      templates.get
    },
    awsCredentials := {
      awsCredentialsProvider.getCredentials
    },

    watchSources <++= templates map identity,
    stackValidate <<= (awsCredentials, templates, streams) map {
      (credentials, files, s) =>


        def validateTemplate(client: AmazonCloudFormationClient, log: Logger)(template: sbt.File): (File, Try[List[String]]) = {
          (template, Try {
            val request: ValidateTemplateRequest = new ValidateTemplateRequest()
            request.setTemplateBody(IO.read(template))
            val result = client.validateTemplate(request)
            log.debug(s"result from validating $template : $result")
            log.info(s"validated $template")
            result.getParameters.toList.map(_.getParameterKey)
          })
        }

        val client = new AmazonCloudFormationClient(credentials)
        val results: Seq[(File, Try[List[String]])] = files.map(validateTemplate(client, s.log))
        results.foreach {
          tr =>
            tr._2 match {
              case Failure(e) => s.log.error(s"validation of ${tr._1} failed with: \n ${e.getMessage}")
              case _ =>
            }
        }

        if (results.exists(_._2.isFailure)) {
          sys.error("some AWS CloudFormation templates failed to validate!")
        }

        files
    }
  )

  lazy val defaultSettings = validationSettings ++ Seq(
    stackRegion := System.getenv("AWS_DEFAULT_REGION"),
    stackTemplate <<= templates {
      files =>
        if (files.isEmpty)
          throw new FileNotFoundException("*.template not found in this project")
        IO.read(files.head)
    },
    stackName <<= normalizedName,
    stackCapabilities := Seq()
  ) ++ makeOperationConfig(Staging) ++ makeOperationConfig(Production)

  implicit private def parametersToList(params: Parameters): util.Collection[Parameter] = {
    val ps: Iterable[Parameter] = for {
      (k, v) <- params
      p = new Parameter()
    } yield {
      p.setParameterKey(k)
      p.setParameterValue(v)
      p
    }
    ps.toList
  }

  def makeOperationConfig(config: Configuration) = Seq(
    awsCredentials in config <<= awsCredentials,
    stackTemplate in config <<= stackTemplate,
    stackParams in config := Map(),
    stackName in config <<= stackName {
      normName =>
        s"${config.name}-$normName"
    },
    stackRegion in config <<= stackRegion,
    stackCapabilities in config <<= stackCapabilities,
    stackClient in config <<= (stackRegion in config, awsCredentials in config) {
      (region, credentials) =>
        if (region == null)
          throw new IllegalArgumentException("stackRegion must be set")

        val client = new AmazonCloudFormationClient(credentials)
        client.setRegion(Region.getRegion(Regions.fromName(region)))
        client
    },
    stackDescribe in config <<= (stackClient in config, stackName in config, streams) map {
      (cl, stack, s) =>

        val request: DescribeStacksRequest = new DescribeStacksRequest()
        request.setStackName(stack)
        val response = cl.describeStacks(request)
        response.getStacks.toList.foreach(stack => s.log.info(s"${stack.toString}"))
    },
    stackStatus in config <<= (stackClient in config, stackName in config, streams) map {
      (cl, stack, s) =>

        val request: DescribeStacksRequest = new DescribeStacksRequest()
        request.setStackName(stack)
        val response = cl.describeStacks(request)
        response.getStacks.toList.foreach(stack => s.log.info(s"${stack.getStackStatus} - ${stack.getStackStatusReason}"))
    },
    stackCreate in config <<= (stackClient in config, stackName in config, stackTemplate in config, stackParams in config, stackCapabilities in config, streams) map {
      (cl, stack, template, params, capabilities, s) =>

        val request = new CreateStackRequest
        request.setStackName(stack)
        request.setTemplateBody(template)
        request.setCapabilities(capabilities)
        request.setParameters(params)

        val result = cl.createStack(request)

        s.log.info(s"created stack ${request.getStackName} / ${result.getStackId}")
        result.getStackId
    },
    stackDelete in config <<= (stackClient in config, stackName in config, streams) map {
      (cl, stack, s) =>

        val request = new DeleteStackRequest
        request.setStackName(stack)

        cl.deleteStack(request)

        s.log.info(s"deleting stack ${request.getStackName} ")

    },
    stackUpdate in config <<= (stackClient in config, stackName in config, stackTemplate in config, stackParams in config, stackCapabilities in config, streams) map {
      (cl, stack, template, params, capabilities, s) =>

        val request = new UpdateStackRequest
        request.setStackName(stack)
        request.setTemplateBody(template)
        request.setCapabilities(capabilities)
        request.setParameters(params)

        val result = cl.updateStack(request)

        s.log.info(s"updated stack ${request.getStackName} / ${result.getStackId}")
        result.getStackId
    }
  )


}