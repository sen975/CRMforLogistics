import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

type DetailSelection =
  | { kind: 'message'; id: string }
  | { kind: 'callRecord'; id: string }
  | null;

interface DetailPanelContextType {
  selectedDetail: DetailSelection;
  selectMessage: (id: string | null) => void;
  selectCallRecord: (id: string | null) => void;
  clearSelection: () => void;
  detailPanelOpen: boolean;
  setDetailPanelOpen: (open: boolean) => void;
  toggleDetailPanel: () => void;
}

const DetailPanelContext = createContext<DetailPanelContextType>({
  selectedDetail: null,
  selectMessage: () => {},
  selectCallRecord: () => {},
  clearSelection: () => {},
  detailPanelOpen: false,
  setDetailPanelOpen: () => {},
  toggleDetailPanel: () => {},
});

export function DetailPanelProvider({ children }: { children: ReactNode }) {
  const [selectedDetail, setSelectedDetail] = useState<DetailSelection>(null);
  const [detailPanelOpen, setDetailPanelOpen] = useState(false);

  const selectMessage = useCallback((id: string | null) => {
    setSelectedDetail(id ? { kind: 'message', id } : null);
    if (id) setDetailPanelOpen(true);
  }, []);

  const selectCallRecord = useCallback((id: string | null) => {
    setSelectedDetail(id ? { kind: 'callRecord', id } : null);
    if (id) setDetailPanelOpen(true);
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedDetail(null);
  }, []);

  const toggleDetailPanel = useCallback(() => {
    setDetailPanelOpen((open) => !open);
  }, []);

  return (
    <DetailPanelContext.Provider
      value={{
        selectedDetail,
        selectMessage,
        selectCallRecord,
        clearSelection,
        detailPanelOpen,
        setDetailPanelOpen,
        toggleDetailPanel,
      }}
    >
      {children}
    </DetailPanelContext.Provider>
  );
}

export function useDetailPanel() {
  return useContext(DetailPanelContext);
}
