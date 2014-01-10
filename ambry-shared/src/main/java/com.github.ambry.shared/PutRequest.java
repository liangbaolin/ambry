package com.github.ambry.shared;

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.BlobPropertySerDe;
import com.github.ambry.utils.Utils;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * A Put Request used to put a blob
 */
public class PutRequest extends RequestOrResponse {

  private ByteBuffer usermetadata;
  private InputStream data;
  private BlobId blobId;
  private long sentBytes = 0;
  private BlobProperties properties;

  private static final int UserMetadata_Size_InBytes = 4;

  public PutRequest(int correlationId, String clientId, BlobId blobId, BlobProperties properties,
                    ByteBuffer usermetadata, InputStream data) {
    super(RequestResponseType.PutRequest, Request_Response_Version, correlationId, clientId);

    this.blobId = blobId;
    this.properties = properties;
    this.usermetadata = usermetadata;
    this.data = data;
  }

  public static PutRequest readFrom(DataInputStream stream, ClusterMap map) throws IOException {
    short versionId = stream.readShort();
    // ignore version for now
    int correlationId = stream.readInt();
    String clientId = Utils.readIntString(stream);
    BlobId id = new BlobId(stream, map);
    BlobProperties properties = BlobPropertySerDe.getBlobPropertyFromStream(stream);
    ByteBuffer metadata = Utils.readIntBuffer(stream);
    InputStream data = stream;
    return new PutRequest(correlationId, clientId, id, properties, metadata, data);
  }

  public BlobId getBlobId() {
    return blobId;
  }

  public BlobProperties getBlobProperties() {
    return properties;
  }

  public ByteBuffer getUsermetadata() {
    return usermetadata;
  }

  public InputStream getData() {
    return data;
  }

  public long getDataSize() {
    return properties.getBlobSize();
  }

  @Override
  public long sizeInBytes() {
    // sizeExcludingData + blob size
    return sizeExcludingData() + properties.getBlobSize();
  }

  private int sizeExcludingData() {
    // header + blobId size + blobId + metadata size + metadata + blob property size
    return (int)super.sizeInBytes() + blobId.sizeInBytes() + UserMetadata_Size_InBytes + usermetadata.capacity() +
           BlobPropertySerDe.getBlobPropertySize(properties);
  }

  @Override
  public void writeTo(WritableByteChannel channel) throws IOException {
    if (bufferToSend == null) {
      bufferToSend = ByteBuffer.allocate(sizeExcludingData());
      writeHeader();
      bufferToSend.put(blobId.toBytes());
      BlobPropertySerDe.putBlobPropertyToBuffer(bufferToSend, properties);
      bufferToSend.putInt(usermetadata.capacity());
      bufferToSend.put(usermetadata);
      bufferToSend.flip();
    }
    while (sentBytes < sizeInBytes()) {
      if (bufferToSend.remaining() > 0) {
        int toWrite = bufferToSend.remaining();
        int written = channel.write(bufferToSend);
        sentBytes += written;
        if (toWrite != written || sentBytes == sizeInBytes()) {
          break;
        }
      }
      logger.trace("sent Bytes from Put Request {}", sentBytes);
      bufferToSend.clear();
      int dataRead = data.read(bufferToSend.array(), 0, (int)Math.min(bufferToSend.capacity(),
                                                                      (sizeInBytes() - sentBytes)));
      bufferToSend.limit(dataRead);
    }
  }

  @Override
  public boolean isSendComplete() {
    return sizeInBytes() == sentBytes;
  }
}