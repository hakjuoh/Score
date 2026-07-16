import {AiChatAttachmentService} from './ai-chat-attachment.service';

describe('AiChatAttachmentService', () => {
  const service = new AiChatAttachmentService();

  it.each([
    ['payload.zip', 'application/zip'],
    ['payload.tar', 'application/x-tar'],
    ['payload.tgz', 'application/gzip']
  ])('rejects archive files that the server cannot process: %s', (name, type) => {
    const file = new File(['archive'], name, {type});

    expect(service.isSupportedAttachment(file, service.attachmentMediaType(file))).toBe(false);
  });

  it('keeps text, image, and PDF attachment support', () => {
    expect(service.isSupportedAttachment(new File(['x'], 'a.txt', {type: 'text/plain'}), 'text/plain')).toBe(true);
    expect(service.isSupportedAttachment(new File(['x'], 'a.png', {type: 'image/png'}), 'image/png')).toBe(true);
    expect(service.isSupportedAttachment(new File(['x'], 'a.pdf', {type: 'application/pdf'}), 'application/pdf')).toBe(true);
  });

  it('does not let a text-looking extension override an explicitly unsupported media type', () => {
    const file = new File(['archive'], 'payload.txt', {type: 'application/zip'});

    expect(service.isSupportedAttachment(file, service.attachmentMediaType(file))).toBe(false);
  });
});
