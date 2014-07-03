package garde

import scalaz._
import Scalaz._

package object security {
  type DomainValidation[+α] = Validation[NonEmptyList[String], α]

  /**
   * Trait for validation errors
   */
  trait ValidationKey {
    def failNel = this.toString.failNel
    def nel = NonEmptyList(this.toString)
    def failure = this.toString.fail
  }

  object CommonValidations {
    /**
     * Validates that a string is not null and non empty
     *
     * @param s String to be validated
     * @param err ValidationKey
     * @return Validation
     */
    def checkString(s: String, err: ValidationKey): Validation[String, String] =
      if (s == null || s.isEmpty) err.failure else s.success

    /**
     * Validates that the expected version of an aggregate matches the current version.
     *
     * @param expected Long expected version
     * @param current long current version
     * @param err ValidationKey
     * @return Validation
     */
    def checkVersion(expected: Long, current: Long, err: ValidationKey): Validation[String, Long] =
      if (expected == current) expected.success else err.failure
  }
}
