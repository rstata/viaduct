package model.testing

import model.PathComponent
import model.Stamp

/** Constructs an occurrence stamp for cross-module semantic tests. */
fun occurrenceStampOf(path: List<PathComponent>): Stamp.Occurrence =
    Stamp.Occurrence.of(resolverPath = path)
