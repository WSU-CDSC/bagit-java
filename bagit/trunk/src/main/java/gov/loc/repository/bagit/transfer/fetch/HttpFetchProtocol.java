package gov.loc.repository.bagit.transfer.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import static java.text.MessageFormat.format;

import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import gov.loc.repository.bagit.transfer.BagTransferException;
import gov.loc.repository.bagit.transfer.FetchProtocol;
import gov.loc.repository.bagit.transfer.FetchedFileDestination;
import gov.loc.repository.bagit.transfer.FileFetcher;

@SuppressWarnings("serial")
public class HttpFetchProtocol implements FetchProtocol
{
    private static final Log log = LogFactory.getLog(HttpFetchProtocol.class);
    
    public HttpFetchProtocol()
    {
        this.connectionManager = new MultiThreadedHttpConnectionManager();
        this.client = new HttpClient(this.connectionManager);
        this.state = new HttpState();
        
        // There's no state in this class right now, so just
        // return the same one over and over.
        this.instance = new HttpFetcher();
    }
    
    @Override
    public FileFetcher createFetcher(URI uri, Long size) throws BagTransferException
    {
        return this.instance;
    }

    private static final HttpClientParams defaultParams = new HttpClientParams() {{
        setAuthenticationPreemptive(false);
        setParameter(USER_AGENT, "BagIt Library Parallel Fetcher ($Id$)");
    }};
    
    private final MultiThreadedHttpConnectionManager connectionManager;
    private final HttpClient client;
    private final HttpState state;
    private final HttpFetcher instance;
    
    private class HttpFetcher implements FileFetcher
    {
        @Override
        public void fetchFile(URI uri, Long size, FetchedFileDestination destination) throws BagTransferException
        {
            log.trace(format("Fetching {0} to destination {1}", uri, destination.getFilepath()));
            
            GetMethod method = new GetMethod(uri.toString());
            method.setParams(defaultParams);
            
            InputStream in = null;
            OutputStream out = null;
            
            try
            {
                log.trace("Executing GET");
                int responseCode = client.executeMethod(HostConfiguration.ANY_HOST_CONFIGURATION, method, state);
                log.trace(format("Server said: {0}", method.getStatusLine()));  
                
                if (responseCode != HttpStatus.SC_OK)
                    throw new BagTransferException(format("Server returned code {0}: {1}", responseCode, uri));

                log.trace("Opening destination.");
                out = destination.openOutputStream(false);
                in = method.getResponseBodyAsStream();
                
                log.trace("Copying from network to destination.");
                long bytesCopied = IOUtils.copyLarge(in, out);
                log.trace(format("Successfully copied {0} bytes.", bytesCopied));
            }
            catch (HttpException e)
            {
                throw new BagTransferException(format("Could not transfer URI: {0}", uri), e);
            }
            catch (IOException e)
            {
                throw new BagTransferException(format("Could not transfer URI: {0}", uri), e);
            }
            finally
            {
                method.releaseConnection();
                IOUtils.closeQuietly(in);
                IOUtils.closeQuietly(out);
            }
        }
    }
}
