package de.gnampf.syncusgnampfus;

import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;
import de.willuhn.util.ProgressMonitor;

/**
 * Marker-Interface fuer die vom Plugin unterstuetzten Jobs.
 */
public interface SyncusGnampfusSynchronizeJob extends SynchronizeJob
{
  // Hier koennen wir jetzt eigene Funktionen definieren, die dann von
  // der JobGroup im ExampleSynchronizeBackend ausgefuehrt werden.
  
  /**
   * Fuehrt den Job aus.
   * @throws Exception
   */
  public void execute(ProgressMonitor monitor) throws Exception;

}