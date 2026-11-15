"""Exercise the PC channel envelope with stock Java crypto APIs used by Android."""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from protocol import ChannelCodec

JAVA = r'''
import java.util.*;
import java.security.*;
import java.security.spec.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;

public class VerifyEnvelope {
  static byte[] hex(String s) {
    byte[] a = new byte[s.length()/2];
    for(int i=0;i<a.length;i++) a[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);
    return a;
  }
  public static void main(String[] args) throws Exception {
    String id = args[0];
    byte[] data = Base64.getDecoder().decode(args[1]);
    byte[] key = hex(args[2]);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,data,0,12));
    c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
    byte[] plain = c.doFinal(data,12,data.length-12);
    int pos=0, nameLen=plain[pos++] & 255;
    byte[] name=Arrays.copyOfRange(plain,pos,pos+nameLen); pos+=nameLen;
    byte[] pub=Arrays.copyOfRange(plain,pos,pos+65); pos+=65;
    long ts=ByteBuffer.wrap(plain,pos,8).getLong(); pos+=8;
    int sigLen=plain[pos++] & 255;
    byte[] sig=Arrays.copyOfRange(plain,pos,pos+sigLen); pos+=sigLen;
    byte[] body=Arrays.copyOfRange(plain,pos,plain.length);
    ByteArrayOutputStream signed=new ByteArrayOutputStream();
    signed.write(id.getBytes(StandardCharsets.UTF_8));
    signed.write(ByteBuffer.allocate(8).putLong(ts).array());
    signed.write(nameLen); signed.write(name); signed.write(body);
    byte[] header=hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    ByteArrayOutputStream full=new ByteArrayOutputStream(); full.write(header); full.write(pub);
    PublicKey keyPub=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(full.toByteArray()));
    Signature verify=Signature.getInstance("SHA256withECDSA");
    verify.initVerify(keyPub); verify.update(signed.toByteArray());
    if(!verify.verify(sig) || body[0] != 0) throw new SecurityException("invalid signature or type");
    System.out.print(new String(name,StandardCharsets.UTF_8)+":"+new String(body,1,body.length-1,StandardCharsets.UTF_8));
  }
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK not available')
class JavaInteropTests(unittest.TestCase):
    def test_java_decrypts_and_verifies_pc_envelope(self):
        key = bytes(range(32))
        codec = ChannelCodec('test-password', 'Alice', key=key)
        packet = json.loads(codec.encode('Bonjour du PC'))
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / 'VerifyEnvelope.java'
            source.write_text(JAVA)
            subprocess.run(['javac', str(source)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', d, 'VerifyEnvelope', packet['i'], packet['p'], key.hex()],
                                    check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout, 'Alice:Bonjour du PC')
            failed = subprocess.run(['java', '-cp', d, 'VerifyEnvelope', packet['i'], packet['p'], ('ff' * 32)],
                                    capture_output=True, text=True)
            self.assertNotEqual(failed.returncode, 0)


if __name__ == '__main__':
    unittest.main()
