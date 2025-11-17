"""Models for XBT (XML Built-in Type) tools.

XBTs (XML Built-in Types) are fundamental XML Schema Definition (XSD) data types that serve as
the foundation for data type definitions in Core Components. They represent primitive data types
such as string, integer, decimal, date, boolean, and their specialized subtypes.

XBTs form a type hierarchy through subtype relationships. For example:
- anyType (root type)
  - anySimpleType (subtype of anyType)
    - string (subtype of anySimpleType)
      - normalizedString (subtype of string)
        - token (subtype of normalizedString)
          - language (subtype of token)
    - decimal (subtype of anySimpleType)
      - integer (subtype of decimal)
        - nonNegativeInteger (subtype of integer)
          - positiveInteger (subtype of nonNegativeInteger)
    - dateTime, date, time, duration, boolean, float, double, etc. (all subtypes of anySimpleType)

Each XBT includes mappings to other data representation formats (JSON Schema Draft 05, OpenAPI 3.0, Avro)
to support interoperability across different systems and standards.
"""

from services.models.xbt import XbtDto


class GetXbtResponse(XbtDto):
    """Response for get_xbt tool."""
    pass
