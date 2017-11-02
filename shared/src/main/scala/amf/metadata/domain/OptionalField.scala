package amf.metadata.domain

import amf.metadata.{Field, Obj}

/**
  * Determines if the field is optional for merging.
  */
trait OptionalField extends Obj {

  val optional: Field

}
