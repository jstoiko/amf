package amf.builder

import amf.metadata.domain.OrganizationModel.{Email, Name, Url}
import amf.domain.{Fields, Organization}

/**
  *
  */
class OrganizationBuilder extends Builder[Organization] {

  def withUrl(url: String): OrganizationBuilder = set(Url, url)

  def withName(name: String): OrganizationBuilder = set(Name, name)

  def withEmail(email: String): OrganizationBuilder = set(Email, email)

  override def build: Organization = Organization(fields)
}

object OrganizationBuilder {
  def apply(): OrganizationBuilder = new OrganizationBuilder()

  def apply(fields: Fields): OrganizationBuilder = apply().copy(fields)
}
