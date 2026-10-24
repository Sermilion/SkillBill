package skillbill.ports.projectstore

import skillbill.ports.projectstore.model.ProjectCatalogEntry

interface ProjectCatalogPort {
  /**
   * Lists every project store whose record parses, newest `lastSeen` first with unparsable times last, then by
   * project id. Malformed records are skipped and a missing store directory yields an empty list.
   */
  fun list(): List<ProjectCatalogEntry>
}
