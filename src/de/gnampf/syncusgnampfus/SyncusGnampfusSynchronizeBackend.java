package de.gnampf.syncusgnampfus;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobProvider;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.SynchronizeJobProvider;
import de.willuhn.logging.Logger;
import de.willuhn.util.ProgressMonitor;

public abstract class SyncusGnampfusSynchronizeBackend extends AbstractSynchronizeBackend 
{
    @Resource
    private SynchronizeEngine engine = null;

	@Override
    protected JobGroup createJobGroup(Konto k) {
        return new SyncusGnampfusJobGroup(k);
    }
    

    @Override
    protected Class<? extends SynchronizeJobProvider> getJobProviderInterface() {
        return SyncusGnampfusSynchronizeJobProvider.class;
    }

    /**
     * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
     */
    @Override
    public List<String> getPropertyNames(Konto konto)
    {
    	return null;
    }

    @Override
    public List<Konto> getSynchronizeKonten(Konto k)
    {
        List<Konto> list = super.getSynchronizeKonten(k);
        List<Konto> result = new ArrayList<Konto>();
        
        // Wir wollen nur die Offline-Konten und jene, bei denen Scripting explizit konfiguriert ist
        for (Konto konto:list)
        {
            if (konto != null)
            {
            	SynchronizeBackend backend = engine.getBackend(konto);
            	if (backend != null && backend.equals(this))
            	{
	                result.add(konto);
            	}
            }
        }
        
        return result;
    }

    protected class SyncusGnampfusJobGroup extends JobGroup
    {
        protected SyncusGnampfusJobGroup(Konto k) {
            super(k);
        }
        
        @Override
        protected void sync() throws Exception
        {
			////////////////////////////////////////////////////////////////////
			// lokale Variablen
			ProgressMonitor monitor = worker.getMonitor();
			String kn               = this.getKonto().getLongName();
			
			////////////////////////////////////////////////////////////////////
			
			try
			{
				this.checkInterrupted();
				
				monitor.log(" ");
				monitor.log(i18n.tr("Synchronisiere Konto: {0}",kn));
				
				Logger.info("processing jobs");
				for (Object job:this.jobs)
				{
					this.checkInterrupted();
					
					SyncusGnampfusSynchronizeJob j = (SyncusGnampfusSynchronizeJob) job;
					j.execute();
				}
			}
			catch (Exception e)
			{
				throw e;
			}
        }
    }
}
