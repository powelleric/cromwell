package cromwell.docker.registryv2.flows.aws

object EcrUtils {

  case class EcrUnauthorized() extends Exception
  case class EcrNotFound() extends Exception
  case class EcrForbidden() extends Exception

}
